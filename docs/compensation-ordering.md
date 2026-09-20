# Compensation ordering: the sweeper releases outside the lock, cancel inside it

2026-09-20. Companion to `review-2026-09-18.md` finding #1.

Two compensation paths free a seat that event-service committed and booking-service rolled
back. They look like the same operation and they are not: the sweeper releases OUTSIDE the
booking's row lock and is safe because of the expiry check, and cancel must release INSIDE it
because that check is one cancel deliberately does not make. This doc records the reasoning,
because the difference is invisible from either call site alone.

What follows is why they are not the same shape, which is the part that cannot be recovered by
reading either call site.

---

## The sweeper

### Where the HTTP call sits

In `ExpiredBookingSweeper.releaseSeats(bookingId)`, called from the per-booking loop in
`sweep()` immediately before `bookingService.expireIfPending`.

Neither `sweep()` nor `releaseSeats()` is `@Transactional`. The booking is read through
`BookingService.seatsOf(bookingId)`, which is `@Transactional(readOnly = true)` and therefore
**opens and commits its own transaction before the HTTP call starts**; `expireIfPending` opens a
second one after it returns. So the call sits between two transactions and inside neither — no
pooled connection is held across it, which is what finding #5 is about.

That isn't asserted in a comment alone. `releasesSeatsBeforeFlippingToExpired` reads
`TransactionSynchronizationManager.isActualTransactionActive()` from inside the release stub and
asserts it is `false`.

### The three tests

In `ExpiredBookingSweeperMySqlTest`:

1. **`releasesSeatsBeforeFlippingToExpired`** — asserts the ordering from the *database*, not from
   Mockito's call order: the stub reads the booking's status mid-release and it is still
   `PENDING`, and it is `EXPIRED` afterwards. Plus the no-transaction assertion above.
2. **`failedReleaseLeavesTheBookingPending`** — release throws, `sweep()` returns 0 without failing
   the pass, booking stays `PENDING`. Then event-service "recovers" and a second `sweep()` expires
   it, showing the retry needs no machinery beyond the booking still being a candidate.
3. **`bookingThatNeverConfirmedReleasesNothingAndStillExpires`** — `released: 0`, still `EXPIRED`,
   and the call was made anyway, since the sweeper can't know which booking orphaned a seat
   without asking.

### The paired comment

At `ExpiredBookingSweeper.releaseSeats` and at `BookingService.confirm`'s expiry check, each
naming the other: the release holds no row lock, and what makes that safe is that the sweeper
selects only `expiresAt`-passed bookings and confirm refuses exactly those. Both say that
relaxing confirm's check — a grace period, absorbed clock skew — makes the sweeper unsafe.

### 503 carve-out

`EventServiceUnavailableException` carries a `userMessage`, defaulting to today's text; only the
`markSeatsBooked` catch overrides it with "The booking could not be confirmed, please retry"; the
handler renders `ex.getUserMessage()`. No new type, no branching.

Two tests in `EventClientTest` pin that the confirm path and the seat-map path say different
things, and one in `ConfirmSoldSeatMySqlTest` pins that the handler renders it rather than its own
sentence.

---

## Cancel

The obvious move is to copy the sweeper: release first, outside the transaction, then flip. That is
wrong, and the reason is sharper than "the window is different".

### The hazard is real, but only outside the lock

Cancel and confirm both start with `lockOwnedBooking` → `SELECT … FOR UPDATE`. If the release sits
*after* that lock, the race cannot occur: cancel blocks at the lock until confirm
commits, then reads `CONFIRMED` and throws `BookingNotPendingException` before reaching any
release. `cancelWaitsForConcurrentConfirm` is exactly that, and it passes today.

The race becomes reachable **only if the release is placed where the sweeper's is** — before the
transaction, outside the lock. Then cancel is unlocked and unordered against confirm, and the
sequence runs start to finish - a confirm marks the seats, cancel frees them, the confirm then
commits CONFIRMED, and the booking holds a confirmation for seats the system has put back on sale.
So "same as the sweeper" is the one framing that breaks it.

### Why the sweeper's argument cannot transfer — the precise reason

The sweeper is safe outside the lock because it releases only bookings whose `expiresAt` has
passed, and confirm refuses exactly those. Cancel's own javadoc says it is *"deliberately NOT
checked against expires_at"*, with a good reason (the answer would otherwise depend on where the
60-second cycle happens to be).

So cancel hasn't merely got a narrower version of the sweeper's safety property — it has
explicitly declined the predicate the entire argument rests on. Nothing to transfer.

### What cancel already does with Redis, and what it actually tells us

`registerHoldReleaseAfterCommit` hangs the hold release off an `afterCommit` callback, reasoning
that releasing inside a transaction that might roll back would free seats for a booking that stays
PENDING. That reasoning does carry over. But two things break the analogy:

- **`afterCommit` does not get you out of the transaction.** It fires before `afterCompletion`, and
  the JDBC connection is released during cleanup *after* that. Work done in an `afterCommit`
  callback still holds the pooled connection. For a sub-millisecond Redis call nobody cared; for an
  HTTP call with a 5s read timeout it is finding #5 with extra steps. So after-commit buys none of
  the connection relief it appears to.
- **The Redis release has a backstop and the event_db release has none.** Its comment says so:
  *"if the release itself fails, the TTL collects the keys."* Nothing collects an orphaned
  `show_seats` row. The precedent's failure mode is "we lose nothing"; here it is "we lose it
  permanently."

### "If release-after-commit, what catches a failed release?" — nothing. Confirmed.

A `CANCELLED` booking with `BOOKED` seats is invisible to the sweeper, which selects `PENDING`
only. That state would be permanent, and no retry loop invented at the call site fixes it -
something has to be looking for it, and nothing is.

And it's worth being precise about how a PENDING booking comes to own seats at all: only if a
confirm marked them in event_db and then rolled back locally — the finding #1 orphan itself.
Cancel's release is *exclusively* an orphan-cleanup path. Which means if cancel can't complete the
release, the right outcome is not "cancel anyway and lose the orphan" — it's to leave the booking
where something will come back for it.

### The answer: release inside the transaction, after the lock and after the PENDING check

- **Safety mechanism: the row lock.** Not the clock, not the commit boundary. A concurrent confirm
  is serialized against it, and the `PENDING` re-check under that lock is what refuses a booking
  confirm has already taken.
- **Retry mechanism: the rollback.** A failed release throws, the `CANCELLED` flip never commits,
  the booking stays `PENDING` — which is the sweeper's own candidate condition. The sweeper then
  retries the identical release on its next pass, using the machinery the sweeper already has. This is
  the decisive argument: rollback is the only thing that hands the problem to the one component
  that will come back for it. The user gets a 503 and can retry; nothing is inconsistent either
  way.
- **Cost: one pooled connection across one HTTP call**, bounded by the client timeout, on a rare
  user-initiated single-booking path. The sweeper can't afford this because it's a loop — N
  bookings would mean N connections held across N HTTP calls inside one 60s cycle, which is how you
  exhaust a pool. Cancel is one request for one booking. The asymmetry is real and worth writing
  down at the call site.
- **Failure in the other direction is benign:** release succeeds, local commit fails → booking stays
  `PENDING` with seats `AVAILABLE` and holds still in Redis, which is just the ordinary pre-confirm
  state. The sweeper expires it later and its release is a no-op.

So: compensate-first-then-flip survives, but for a different reason than the sweeper's, and the
placement is the opposite of the sweeper's — inside the transaction rather than outside it. The two
paths should not be made to look alike. The comment at cancel's release site contrasts them
explicitly, because "why doesn't cancel do it the way the sweeper does" is the first question
anyone will ask.

### The rejected fallback, and why it stays rejected

The alternative to paying the connection cost is to widen the sweeper's candidate query to also
pick up `CANCELLED` bookings that may still own seats. It was considered and declined: it is a new
scan and a new candidate definition, and with the release inside cancel's transaction there is no
`CANCELLED`-with-seats state for it to find.

**If such a row is ever observed, that is evidence this design is wrong.** It should be
investigated, not quietly collected by a widened net - a net would hide the only signal that the
ordering argument above has a hole in it.
