-- release_seats.lua - drop this booking's holds, and only this booking's holds.
--
-- WHY THIS IS A SCRIPT AND NOT A JAVA LOOP
-- ========================================
-- The operation is "delete this key IF it still belongs to me", and check-then-act
-- is exactly the shape that breaks under concurrency. In Java it would be:
--
--     GET seat:hold:1:5        -> "42", so booking 42 decides to delete it
--                              <- the key expires here; booking 99 does
--                                 SET seat:hold:1:5 "99" NX and wins the seat
--     DEL seat:hold:1:5        -> booking 42 deletes booking 99's brand-new hold
--
-- Booking 42 has now silently released a seat that belongs to booking 99, and
-- booking 99 will not find out until its confirm fails. The GET and the DEL have
-- to be one indivisible step, which is what a script gives.
--
-- This is the same compare-and-delete used for distributed locks, and for the
-- same reason: an unconditional DEL is a lock that anyone can unlock.
--
-- CONTRACT
-- ========
--   KEYS[1..N]  the seat hold keys to release
--   ARGV[1]     bookingId - the value a key must currently hold to be deleted
--
-- RETURNS
--   the number of keys actually deleted. It is normal for this to be less than
--   #KEYS: the TTL may already have collected some, in which case there is
--   nothing to release and nothing has gone wrong.
--
-- SAFE TO CALL TWICE. A second call finds no key still carrying this bookingId
-- and deletes nothing, so a retry after a network failure cannot do damage.

local booking_id = ARGV[1]
local deleted = 0

for i = 1, #KEYS do
    -- GET returns the stored string, or nil if the key is gone (expired, or
    -- never existed). The Lua client turns that nil into false, which never
    -- equals booking_id, so an expired hold simply falls through.
    local owner = redis.call('GET', KEYS[i])

    -- String comparison, and it must stay a string comparison: booking_id came
    -- from ARGV as text and the stored value is text. Converting either side to
    -- a number would make "042" and "42" compare equal and let one booking
    -- release another's hold.
    if owner == booking_id then
        -- DEL returns the number of keys removed - 1 here, since the GET above
        -- proved this one exists and nothing can have changed in between.
        deleted = deleted + redis.call('DEL', KEYS[i])
    end
end

-- A Lua number comes back to Java as a Long via the script's declared result
-- type. The caller logs it rather than acting on it: releasing is best-effort
-- cleanup, and the TTL is the real guarantee that holds do not leak.
return deleted
