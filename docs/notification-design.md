# notification-service — design notes

The consumer end of `booking.confirmed`. One confirmation email per booking, delivered
at-least-once and de-duplicated on arrival.

This document records the decisions that are not obvious from the code, and the evidence for
the two claims the service exists to make. The code itself carries the fuller reasoning; this
is the map.

---

## Shape

- Port **8085**, serving the actuator health endpoint and **nothing else**. There are no
  controllers in the module, and `NoHttpSurfaceTest` fails the build if one appears. Work
  arrives from Kafka, not from a request.
- **No database.** The only state is one Redis key per handled `eventId` with a 24-hour TTL.
  "Database per service" has nothing to hold here, so there is no schema and no migration.
- Consumer group `notification-service`, `auto-offset-reset: earliest`, auto-commit **off**,
  listener `concurrency: 1`.

---

## The ordering: check, then SEND, then mark

The email and the dedupe key are two systems and cannot be written atomically. One of them is
second, and which one decides how the service fails:

| Order | Crash in between | Consequence |
|---|---|---|
| mark → send | the event is remembered as handled | the redelivery is **skipped**; nobody is ever told their booking is confirmed — **a support ticket** |
| **send → mark** | the event is not yet remembered | the redelivery sends a **second copy** — **an annoyance** |

A duplicate confirmation is strictly less bad than a missing one, so the send goes first. The
window is one Redis write wide, but the ordering is what decides which way the system fails
when it does.

**This is verified by mutation, not only by assertion.** Swapping the two statements in
`BookingConfirmedListener` makes `doesNotMarkSeenWhenTheSendFails` fail. A test that does not
fail when the thing it guards is broken is not evidence.

### One key per eventId, not one Redis Set

A Set is the more literal reading of "dedupe with a Redis set" and is wrong for the
requirement: a Set has a single TTL for the whole key, so every remembered event would expire
together rather than 24 hours after its own arrival, and members would accumulate with nothing
to evict them in between. One key each is what a per-event TTL actually is.

### Why check-then-act needs no lock — and when it would

Between the check and the mark, another consumer could in general handle the same event and
both would send. It cannot happen here: the topic has one partition and listener concurrency
is 1, so one thread in one instance ever runs the sequence.

**That is a property of the configuration, not of the code.** Raising concurrency, or running
a second instance with more partitions, makes it a real race. The fix is then `SET NX` as an
atomic claim — which trades this ordering for the other one, and so must be a deliberate
decision about which failure is preferred, not a quiet addition of a flag.

---

## Two dependencies, different criticality

| | auth-service | event-service |
|---|---|---|
| Supplies | the recipient's email address | show title, venue, seat labels |
| Criticality | **REQUIRED** | **BEST-EFFORT** |
| On failure | no send; the event is retried | send anyway, with ids |
| Failures classified? | **every one** | **none** |

The asymmetry in the last row is the design, not an inconsistency. `AuthClient` examines every
failure because the verdict changes what happens next — retry, or give up and log.
`EventClient` examines none because every failure leads to the same line, so there is nothing
to decide. Sorting failures into categories that all reach the same outcome would be ceremony,
not rigour. `EventClient.fetchShow` cannot throw; the signature is the contract.

> "Seats C2, C3 for Coldplay" is a better demo. A confirmation that never arrives because a
> cosmetic lookup failed is a worse system.

### Transient vs permanent, on the required lookup

| Status | Verdict | Action |
|---|---|---|
| connection refused, timeout, 5xx | TRANSIENT | throw; offset uncommitted; retried until it succeeds |
| **404** | PERMANENT | ERROR log, `markSeen`, commit, continue — the user is deleted |
| **401 / 403** | PERMANENT | ERROR log with **distinct wording** — *this service* is misconfigured |
| other 4xx | PERMANENT | ERROR log — the request is malformed and will be next time too |

**Retrying a permanent failure is not caution, it is an outage.** `booking.confirmed` has one
partition, so a message that can never succeed and is never committed past blocks the
partition head — one deleted user would stop every confirmation email in the system,
indefinitely.

The 404 and 401/403 cases are logged differently on purpose: a deleted user is odd data and
needs a support response; a 403 is a broken deployment and needs an engineer. A log search
that cannot tell them apart sends the wrong person.

auth-service answering **404** rather than 401 for a missing user is what makes this
distinction available at all. That is deliberate on the auth-service side and recorded there
(`UserNotFoundException`), because `/api/auth/me` answers 401 for the same condition.

### The cost, stated rather than hidden

For a permanent failure, **the ERROR line is the only record the email ever existed.** So it
carries the `eventId`, `bookingId`, `userId` and the causing status — enough for someone
reading it six weeks later to find the booking and contact the customer.

The production answer is a **dead-letter topic**: the message goes somewhere durable instead of
only to a log, and can be replayed once the cause is fixed. There is none here because
CLAUDE.md fixes this project at exactly one topic. Log-and-drop is a deliberate trade against a
stated constraint, not an oversight.

---

## Retry policy: unlimited attempts, bounded interval

Spring Kafka's stock `DefaultErrorHandler` is `FixedBackOff(0, 9)` — ten immediate attempts,
then it logs and **seeks past the record**. Applied here, a ten-second auth-service blip would
consume all ten attempts in milliseconds and the confirmation would be gone, with no error
anyone would later connect to a missing email. That default is reasonable for a consumer whose
messages are cheap to lose. These are not.

Replaced with an `ExponentialBackOff`: 1s initial, ×2, **`maxInterval` 30s**, `maxElapsedTime`
effectively infinite.

- **Unlimited attempts**, because a transient failure must be retried until it stops being one.
- **Bounded interval**, because unbounded doubling means that after an hour-long outage the
  next attempt is scheduled hours out — auth-service comes back and the consumer sits idle
  while mail queues. Capped, recovery time tracks *when the outage ended*, not *how long it
  lasted*.

The cost is head-of-line blocking on the single partition. It is acceptable only because what
is retried forever is genuinely transient, which is exactly why permanent failures must never
reach this handler.

---

## Rendering

### Show times in Asia/Kolkata

`startsAt` is an `Instant` everywhere — on the wire, in `event_db`, in this service. An instant
has no zone, so printing one requires choosing a zone, and **"18:30 UTC" in a ticket email is
wrong for every recipient of this system.**

This is the display-side consequence of the P2.1 decision to store show times as instants. That
decision is right, and it is affordable only because of a fact outside the code: every venue in
this project is in one timezone that does not observe DST, so a single configured zone
(`app.notification.display-zone`) is correct for every show.

**An international venue breaks that immediately** — London and Mumbai cannot both render
correctly against one setting — and the fix is not a formatter change. The zone would have to
be stored *with the show*, because it is a fact about the venue rather than about this
service's configuration.

### Encoding — checked, not assumed

The body carries ₹ (U+20B9) and — (U+2014). A MIME part defaulting to `us-ascii` turns them
into `?`, which would be discovered in a screenshot rather than in a test. So:

- the message is built with `MimeMessageHelper` on an **explicit UTF-8** charset;
- both glyphs are written in the source as `\u` escapes, so the source file's own encoding can
  never be what breaks them;
- the result was **verified against the real MailHog**, not assumed — see the evidence below.

---

## Evidence

### Kafka durability — an email that outlived the consumer being down

notification-service stopped; a booking confirmed through the gateway; the service started.

```
MailHog before start                      : 0 messages

booking-service, while the consumer is DOWN:
  outbox run: 1 of 1 pending event(s) published to booking.confirmed
  localhost:8085 -> 000   (nothing listening)

notification-service, on start:
  RECEIVED BookingConfirmed for booking 6 (event 9afec982-..., user 5, show 1) from booking.confirmed-0@0
  SENT confirmation for booking 6 (event 9afec982-...) to p44-check-...@example.com - enriched with show details
  RECEIVED BookingConfirmed for booking 7 (event 93759799-..., user 6, show 1) from booking.confirmed-0@1
  SENT confirmation for booking 7 (event 93759799-...) to arshad+p45@example.com - enriched with show details

MailHog after start                       : 2 messages
```

Two events, not one: offset 0 was a booking confirmed during an earlier P4.4 check, long before
this service existed. Both were delivered on first start, because a fresh consumer group with
`auto-offset-reset: earliest` reads the log from the beginning. The broker held them the whole
time — that is the durability, demonstrated rather than recited.

### The delivered message

```
Content-Type              : text/plain; charset=UTF-8
Content-Transfer-Encoding : quoted-printable
Subject (decoded)         : Your booking is confirmed — Coldplay - Music of the Spheres

Hi Arshad,

Your booking is confirmed.

  Coldplay - Music of the Spheres
  Phoenix Arena
  20 September 2026, 12:00 AM IST

  Seats: A3, A4
  Total: ₹900.00

  Booking reference: 7

Show this email at the venue.

— BookMySeat
```

Both non-ASCII glyphs survive: the subject is RFC 2047 encoded
(`=?UTF-8?Q?..._=E2=80=94_...?=`, the em dash as UTF-8), the body is quoted-printable over a
`charset=UTF-8` part, and neither shows mojibake.

### Idempotency — a duplicate consumed, no second email

The **exact payload** was read back out of `booking_db.outbox` and republished to the topic
with `kafka-console-producer`, so the replayed bytes are the original bytes, same `eventId`:

```
MailHog BEFORE replay : 2 messages

  RECEIVED  BookingConfirmed for booking 7 (event 93759799-..., user 6, show 1) from booking.confirmed-0@2
  DUPLICATE event 93759799-... for booking 7 already handled - no second email sent

MailHog AFTER replay  : 2 messages
```

Three records on the topic, two emails:

```
booking.confirmed:0:3

notif:dedupe:9afec982-3be2-4351-9988-ec208035bb3e
notif:dedupe:93759799-e4ed-4f44-9b79-7a92c4c26468
  value 2026-09-16T07:05:39.443078500Z   TTL 86239s (~24.0h)
```

### Tests

13 tests, on real Kafka and real Redis via Testcontainers: the happy path, IST rendering, the
rupee sign, dedupe of a redelivery, the send-fails-so-do-not-mark ordering, auth-service
transient (retried to success) and permanent (marked and committed past, and the *next* event
still delivered), event-service down (degraded send), a malformed payload, and the absence of
any HTTP surface.

`JavaMailSender` is mocked there. These tests assert whether and how many times a message was
handed to the mail layer and what it said; that a message reaches MailHog is the live
demonstration above, which is the right tool for that claim.

---

## Known gaps

- **No dead-letter topic.** Permanently undeliverable events are logged and dropped. See above
  — constrained by the one-topic rule, not overlooked.
- **Single instance assumed.** The lock-free dedupe depends on one partition and concurrency 1.
- **No cleanup of published outbox rows** (booking-service side, unchanged by this work).
- **No Dockerfile / compose entry** for this service, as for event-service and booking-service.
  It runs from the IDE or `java -jar`, with `default` and `docker` profiles.
