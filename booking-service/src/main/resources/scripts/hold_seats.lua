-- hold_seats.lua - acquire a temporary hold on every requested seat, or none.
--
-- WHY THIS IS A SCRIPT AND NOT A JAVA LOOP
-- ========================================
-- Redis executes a script atomically: it runs on the single command-processing
-- thread and no other client's command can interleave with it. That is the whole
-- reason this logic lives here.
--
-- The same loop written in Java would be a sequence of separate round trips:
--
--     SET seat:hold:1:5 ... NX     <- request A wins seat 5
--                                  <- request B wins seat 6 here, in the gap
--     SET seat:hold:1:6 ... NX     <- request A now fails on seat 6
--
-- Between any two of A's commands, B is free to take a seat A was about to ask
-- for. A then has to roll back, and B may already have rolled back too, so both
-- callers fail on seats that are in fact free. Worse, another client observing
-- Redis mid-sequence sees a half-acquired set that never legitimately existed.
-- Inside a script there is no gap and no observable intermediate state: the
-- whole loop, including the rollback below, is one indivisible step.
--
-- CONTRACT
-- ========
--   KEYS[1..N]  the seat hold keys, one per seat, already formatted by
--               SeatHoldService as seat:hold:{showId}:{seatId}. Keys are passed
--               in KEYS and never built from ARGV, so Redis knows every key the
--               script touches - required for correctness under Redis Cluster.
--   ARGV[1]     bookingId, written as each key's VALUE. This is what makes a
--               hold *owned*: release_seats.lua deletes a key only when its
--               value still matches, so one booking can never drop another's.
--   ARGV[2]     ttlSeconds. Every key gets it, so an abandoned checkout expires
--               on its own with no sweeper job and no rows to reconcile.
--
-- RETURNS
--   an empty array        every seat was acquired; the caller owns them all
--   a non-empty array     the keys that were already held by someone else.
--                         Nothing is held by this booking when this is returned:
--                         anything acquired earlier in the call has been undone.

-- ARGV values always arrive as strings. bookingId stays a string because it is
-- compared as one later; the TTL must become a number for SET's EX argument.
local booking_id = ARGV[1]
local ttl_seconds = tonumber(ARGV[2])

-- Keys this invocation actually acquired, so they can be undone if a later seat
-- turns out to be taken. Only keys THIS call created are ever deleted.
local acquired = {}

-- Keys someone else already holds. Collected rather than returned immediately.
local taken = {}

for i = 1, #KEYS do
    -- SET key value NX EX ttl - the entire mutual exclusion, in one command.
    --   NX      set only if the key does not exist. This is the test-and-set:
    --           checking with EXISTS and then setting would reopen the very gap
    --           this script exists to close, even inside the script.
    --   EX ttl  attach the expiry in the same command. A separate EXPIRE could
    --           fail after the SET succeeded and leave a hold that never dies.
    -- Redis replies OK on success and nil on failure; the Lua client converts
    -- that nil to the boolean false, so a plain truth test is enough.
    local ok = redis.call('SET', KEYS[i], booking_id, 'NX', 'EX', ttl_seconds)

    if ok then
        acquired[#acquired + 1] = KEYS[i]
    else
        taken[#taken + 1] = KEYS[i]
    end
end

-- ALL OR NOTHING.
-- The loop above does not stop at the first conflict, deliberately: it tries
-- every seat so the caller can be told about all of them at once. A user booking
-- four seats deserves to see all the taken ones in a single 409 rather than
-- discovering them one retry at a time. Trying the rest costs nothing here,
-- because the rollback is the same either way.
if #taken > 0 then
    for i = 1, #acquired do
        redis.call('DEL', acquired[i])
    end
    -- Note what is NOT a problem: no other client ever saw these keys. The SETs
    -- and these DELs happen inside one atomic script execution, so the partial
    -- acquisition is invisible from outside.
    return taken
end

-- Empty table -> empty array on the wire -> empty List in Java, which the
-- service reads as success. Success is the absence of conflicts, not a flag.
return {}
