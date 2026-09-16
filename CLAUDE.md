# CLAUDE.md — BloodLink Project Context

This file is read automatically at the start of every Claude Code session in this repo.
It is a technical spec, not documentation — every fact below determines how code should be written.
Full narrative justification (why Agile, why these requirements-gathering methods, citations) lives in the
dissertation and is intentionally excluded here; it doesn't change what gets built.

---

## Project Summary

BloodLink is a web-based system for real-time blood bank logistics and voluntary donor management
in Makeni City, Sierra Leone. It connects four actors — Donor, Hospital Staff, Blood Bank/Lab Staff,
System Administrator — on one platform so blood stock, donor eligibility, and hospital requests are
visible in real time instead of tracked manually per facility.

## Tech Stack (fixed — do not substitute without asking)

- **Frontend:** React 18, Vite, plain CSS (CSS custom properties for theming — no Tailwind, no UI library)
- **Backend:** Spring Boot 4.1.1, Java 25, Maven
- **Database:** PostgreSQL, schema managed by Flyway migrations (not `ddl-auto`)
- **Real-time:** Spring WebSocket with STOMP over SockJS
- **Auth:** Spring Security, stateless JWT

## Architecture (three layers)

```
Client Layer      React SPA (browser) — one app, role-based views
Application Layer Spring Boot: REST API (controllers/services) + WebSocket server (notifications)
Data Layer         PostgreSQL, accessed via Spring Data JPA
```

REST is synchronous (requests, CRUD). WebSocket is one-directional server→client push, carrying all
four Notification types and nothing else — there are no `@MessageMapping` handlers and no
client→server messages:

| type | pushed to | raised when |
|---|---|---|
| LOW_STOCK_ALERT | that bank's BLOOD_BANK_STAFF | a stock change drops a group into Low or Critical |
| DONATION_APPEAL | **eligible** donors (Business Rule 2) whose `Donor.blood_type` matches | a blood bank sends an appeal (Business Rule 1) |
| NEW_REQUEST | BLOOD_BANK_STAFF at banks that could fulfil it | a hospital submits a request |
| REQUEST_STATUS_CHANGE | the raising hospital's HOSPITAL_STAFF | a request is approved, rejected or fulfilled |

STOMP over SockJS at `/ws`. The handshake carries no credentials (the browser cannot set a header on
it), so the JWT travels on the STOMP CONNECT frame and a session without a valid one is refused.
Delivery is per-user via `/user/queue/notifications` — nothing is broadcast.

**Every push is also a Notification row, written first, in the same transaction as the event.** The
socket is an accelerator; the row is the record. A client that was offline reads what it missed from
`GET /api/notifications`, so no feature may depend on a push having been received.

**A donation appeal never reaches an ineligible donor.** The Business Rule 2 filter is a donor-safety
gate, not targeting: someone inside their 90-day recovery window must not be prompted toward another
donation. Preview (`GET /api/appeals/reach`) and send (`POST /api/appeals`) share one audience
calculation so the count shown before confirming is the set actually notified.

## Design System (frontend)

Navy `#1C3A5E`, red `#C8102E`, green `#1E7F45` (sufficient/success), amber `#D98A1F` (low/pending),
typeface Inter. Consistent sidebar navigation with role label under the logo, card-based dashboard
layout, status badges colour-coded by state. **Existing components in `/frontend-mockups` already
implement this system — reuse and wire them, don't rebuild from scratch.**

---

## Database Schema (source of truth — match exactly)

### User
| field | type |
|---|---|
| id | UUID, PK |
| full_name | varchar |
| email | varchar |
| password_hash | varchar |
| role | enum: DONOR, HOSPITAL_STAFF, BLOOD_BANK_STAFF, ADMIN |
| phone | varchar — unique (V2) and the login credential; stored in exactly one normalised format, `+232` + 8 digits. See Business Rule 13. |
| blood_bank_id | UUID, FK -> BloodBank, nullable — set only for BLOOD_BANK_STAFF |
| hospital_id | UUID, FK -> Hospital, nullable — set only for HOSPITAL_STAFF |
| created_at | timestamp |
| status | enum: ACTIVE, SUSPENDED — added in V8 for the Admin module's activate/suspend use case, the same kind of confirmed addition as blood_bank_id/hospital_id above. Every account starts ACTIVE. A suspension is enforced immediately, not just at the next login: `JwtAuthenticationFilter` checks it on every authenticated request (one indexed lookup by primary key), and `AuthService.login` checks it again after the password is verified. |

### Donor
| field | type |
|---|---|
| id | UUID, PK |
| user_id | UUID, FK -> User (1:1) |
| blood_type | enum: A+, A-, B+, B-, O+, O-, AB+, AB- |
| date_of_birth | date |
| gender | enum |
| last_donation_date | date, nullable |
| eligibility_status | derived — see Business Rules |
| address | varchar |

### BloodBank
| field | type |
|---|---|
| id | UUID, PK |
| name | varchar |
| location | varchar |
| contact_phone | varchar |

Seeded, not self-registering: **Makeni Regional Blood Bank**, **Holy Spirit Blood Bank**.

### Hospital
| field | type |
|---|---|
| id | UUID, PK |
| name | varchar |
| location | varchar |
| contact_phone | varchar |

Seeded, not self-registering: **Makeni Regional Hospital**, **Holy Spirit Hospital**.

### BloodInventory
| field | type |
|---|---|
| id | UUID, PK |
| blood_bank_id | UUID, FK -> BloodBank |
| blood_type | enum (as above) |
| units_available | int |
| expiry_date | date |
| last_updated | timestamp |

One row per (blood_bank_id, blood_type) — not one row per unit.

### Donation
| field | type |
|---|---|
| id | UUID, PK |
| donor_id | UUID, FK -> Donor |
| blood_bank_id | UUID, FK -> BloodBank |
| donation_date | date |
| units_donated | int |
| blood_type | enum — **verified at donation time; do not just copy from Donor. See Business Rules.** |

### BloodRequest
| field | type |
|---|---|
| id | UUID, PK |
| hospital_id | UUID, FK -> Hospital |
| blood_type | enum |
| units_requested | int |
| urgency_level | enum: ROUTINE, URGENT, EMERGENCY |
| status | enum: PENDING, APPROVED, FULFILLED, REJECTED |
| fulfilled_by_blood_bank_id | UUID, FK -> BloodBank, nullable until approved |
| patient_reference | varchar, nullable — optional ward/patient note from hospital staff |
| created_at | timestamp |

### BloodRequestDecline
| field | type |
|---|---|
| id | UUID, PK |
| blood_request_id | UUID, FK -> BloodRequest, ON DELETE CASCADE |
| blood_bank_id | UUID, FK -> BloodBank, ON DELETE CASCADE |
| declined_at | timestamp |

One row per (blood_request, blood_bank) pair, unique on that pair (`uq_decline_request_bank`) —
added in V9 as the per-bank response table Business Rule 11 anticipated. A row here means "this bank
has declined this request"; it does not change `BloodRequest.status`. See Business Rule 11.

### AppealCooldown
| field | type |
|---|---|
| id | UUID, PK |
| blood_bank_id | UUID, FK -> BloodBank, ON DELETE CASCADE |
| blood_type | enum (as above) |
| last_sent_at | timestamp |
| triggered_tier | enum: CRITICAL, LOW |

One row per (blood_bank, blood_type), unique on that pair (`uq_appeal_cooldown_bank_type`) — added
in V10 for Business Rule 12. Current state, not history: overwritten on each send, **deleted** when
stock recovers past the threshold that triggered it, never appended to. A row existing at all means
"an appeal went out for this group and its shortage has not been resolved since".
`triggered_tier` is how short the bank was **at send time** (CRITICAL under 15 units, LOW 15–29) and
is stored rather than re-derived, because it fixes that cooldown's length and the level stock must
reach to clear it. See Business Rule 12.

### Notification
| field | type |
|---|---|
| id | UUID, PK |
| recipient_user_id | UUID, FK -> User |
| message | text |
| type | enum: LOW_STOCK_ALERT, DONATION_APPEAL, NEW_REQUEST, REQUEST_STATUS_CHANGE |
| is_read | boolean |
| created_at | timestamp |

### Relationships
User 1–1 Donor · Donor 1–N Donation · BloodBank 1–N BloodInventory · BloodBank 1–N Donation ·
BloodBank 1–N BloodRequest (as fulfiller) · Hospital 1–N BloodRequest · User 1–N Notification ·
BloodRequest 1–N BloodRequestDecline · BloodBank 1–N BloodRequestDecline ·
BloodBank 1–N AppealCooldown

---

## Business Rules (decided — do not re-derive differently)

1. **Donation appeals flow from Blood Bank → Donor, never Hospital → Donor.** Hospitals request from
   blood banks; blood banks appeal to donors when their own stock is low. A donor's screen must never
   show a hospital as the source of an appeal. (This was a deliberate architecture correction — see
   Chapter 3, Section 3.5.1 rationale, if narrative context is ever needed.)
2. **Donor eligibility** = `last_donation_date` is more than 90 days ago (or null = always eligible).
   Enforced as a **hard block**, never a warning, in two places: an ineligible donor is never sent a
   donation appeal, and a donation cannot be recorded for one (`POST /api/donations` returns 409 with
   nothing written, and the Record Donation form locks). Both read `Donor.isEligibleToDonate()` and
   `Donor.eligibleFrom()` — the same calculation the donor's own dashboard shows — so they cannot drift.
3. **Stock status thresholds** (per blood group, per blood bank): below 15 units = Critical,
   below 40 units = Low, otherwise Sufficient. Confirm with project owner before changing these numbers.
4. **`Donation.blood_type` is independently recorded, not copied from `Donor.blood_type`.** Blood type
   is re-verified by testing at every donation; the donation record must reflect what was tested at
   that time, not silently follow later edits to the donor's profile.
5. **BloodRequest status lifecycle:** PENDING → APPROVED → FULFILLED, or PENDING → REJECTED.
   Fulfilment must decrement `BloodInventory.units_available` for the fulfilling blood bank.
6. **A hospital's request can be fulfilled by any blood bank**, not only one tied to that hospital —
   there is no fixed hospital-to-blood-bank ownership.
7. **When new stock accumulates onto an existing BloodInventory row** (same blood bank + blood type),
   units add together, but the expiry date is kept as the **earlier** of the old and new expiry dates —
   never extended. A newer batch never masks that some of the combined stock expires sooner.
   Both paths into inventory go through `InventoryService.addBatch`, which owns this rule: "Add New
   Batch" on the Inventory screen, and "Record Donation", which folds a donor's units in the same way.
   A donation's units are given a default expiry of **donation date + 42 days** (`DonationService.SHELF_LIFE_DAYS`),
   the usual shelf life of red cells in additive solution — a default, not a measured date, since
   BloodInventory holds one expiry per (bank, group) and cannot carry a per-bag label.
   **Flagged for review:** confirm 42 days matches the preparation Makeni's labs actually use.
8. **A BLOOD_BANK_STAFF user is tied to exactly one blood bank** via a nullable `blood_bank_id` on
   `User` (null for all other roles). Inventory writes are restricted to that staff member's own bank;
   ADMIN can write to any bank. There is no self-registration path for HOSPITAL_STAFF,
   BLOOD_BANK_STAFF, or ADMIN — only DONOR accounts are created via public registration.
9. **A HOSPITAL_STAFF user is tied to exactly one hospital** via a nullable `hospital_id` on `User`,
   the same pattern as rule 8. A hospital request is stamped with the submitting staff member's own
   hospital automatically — a hospital cannot submit a request on another hospital's behalf. Hospital
   staff only see their own hospital's requests; blood bank staff see every unclaimed request
   (`fulfilled_by_blood_bank_id IS NULL`) plus anything their own bank has already approved or fulfilled.
10. **Approving a request also requires sufficient stock at that moment, not only fulfilling it.**
    Approval removes a request from every other blood bank's queue, so approving without enough stock
    would strand the hospital behind a bank that turns out unable to help. Actual deduction from
    inventory still only happens at fulfilment.
11. **Rejecting a request is per blood bank, not shared.** One bank declining records a
    `BloodRequestDecline` row for that (request, bank) pair and does **not** change
    `BloodRequest.status`: the request stays PENDING and stays fully visible and actionable to every
    other bank. It only becomes `REJECTED` once **every** blood bank has declined it — with two banks
    seeded, that means both. "Every blood bank" is the count of `blood_banks` rows; nothing in the
    schema marks a bank inactive, so existing is what active means, and `RequestService.reject` is the
    one place that would have to learn otherwise if an inactive flag is ever added.
    - A declining bank's own queue must stop showing the request — enforced by the `not exists`
      clause in `BloodRequestRepository.findActionableForBank` (and in
      `countActionableForBankByStatus`, so the stat card and the panel below it agree).
    - A bank cannot decline the same request twice: refused with a 409, and guarded in the database
      by `uq_decline_request_bank`, so two simultaneous clicks cannot inflate the count and close a
      request early.
    - The hospital is notified **only** on the closing decline. A decline that leaves the request live
      is not news the hospital can act on, and `notifyRequestStatusChange` sends nothing for PENDING.
    - Approval and fulfilment are unchanged — a request is already claimed exclusively via
      `fulfilled_by_blood_bank_id` the moment one bank approves it (rules 5, 6, 10).
    This replaces the earlier shared-rejection behaviour, which was flagged for review because one
    bank lacking stock closed a request another bank could have filled. Schema added in `V9`.
12. **A donation appeal is rate-limited per (blood bank, blood group) by a cooldown.** Each send
    writes or overwrites an `AppealCooldown` row stamping `last_sent_at` and the `triggered_tier`
    that stock was in at send time. The Send Appeal button's state is decided in this order, by
    `AppealCooldownPolicy` — the single component both the inventory read path and the send endpoint
    consult, so the button and the server can never disagree:
    1. **30 units or more → disabled.** No shortage, nothing to send.
    2. **A cooldown row within its tier's window → disabled, reading "Appeal Sent."** — 5 hours for
       CRITICAL, 24 hours for LOW, measured from `last_sent_at`. Exactly 5h/24h is still within the
       window ("no more than N hours elapsed"); past it the cooldown lapses.
    3. **Otherwise active.**
    - **Recovery clears a cooldown permanently, at the crossing.** When stock climbs back over the
      threshold that triggered it — 15 for a CRITICAL-triggered row, 30 for a LOW-triggered one —
      the row is **deleted**, by `AppealCooldownPolicy.clearIfRecovered` called from
      `InventoryService.addBatch`. That is the only path by which stock can rise (fulfilment is the
      only one by which it falls), so it is the only place an upward crossing can happen.
      `evaluate` therefore never consults current stock to decide whether a cooldown has ended: a
      row that still exists is one whose shortage was never resolved.
      **A group that recovers and then relapses gets a fresh appeal immediately** — the relapse is a
      new shortage, not the tail of a spent timer belonging to an appeal that already did its job.
      Re-deriving recovery on read instead would resurrect that timer and silence a bank that
      genuinely needs donors again. Detecting the crossing as an event also keeps the read path
      read-only, and matches how `notifyStockCrossing` already treats stock thresholds — with the
      same shared consequence, that stock edited directly in the database (outside the app) raises
      no alert and clears no cooldown.
    - **Appeal thresholds are deliberately not Business Rule 3's badge thresholds.** Rule 3 is a
      display rule (Critical under 15, Low under 40); this is an action rule (an appeal needs fewer
      than 30). So a group holding **30–39 units shows a "Low" badge and still cannot be appealed
      for** — correct, not a bug: asking donors to come in for stock that is merely off target spends
      goodwill the bank will need at 12 units. `AppealTier` owns the appeal numbers, `StockStatus`
      the badge numbers, and they are separate types so a change to one cannot move the other.
    - **Enforced server-side as well as on the button** — `POST /api/appeals` returns 409 for both
      a no-shortage send and one inside a cooldown, since a disabled button is a courtesy, not a
      control. The cooldown row is written only after the send succeeds, inside the same transaction,
      so a failed appeal never leaves a cooldown blocking the retry it did not earn.
    - **This does not change who receives an appeal.** Business Rule 2's 90-day donor-eligibility
      gate is untouched and remains the only thing deciding that. The two answer different questions:
      the cooldown decides whether the bank may ask at all right now, Rule 2 decides which donors it
      is safe to ask. Schema added in `V10`.
13. **Every phone number is stored in exactly one format: `+232` followed by 8 digits, no spaces,
    no leading zero** (e.g. `+23276900005`). This is a storage rule, not a display preference:
    `User.phone` is the login credential (`uq_users_phone`, V2) and is matched by exact string
    equality, so the format a number is stored in decides whether a real person can sign in.
    Before this rule, `076900005` and `+232 76 900 005` were two different accounts' worth of
    string to the system, and a donor who registered one way could not log in the other way.
    - **Accepted on input, from any entry point:** local format (a leading `0` then 8 digits,
      `076900005`) or international format (`+232` then 8 digits, spaced or not,
      `+232 76 900 005`). All spaces are stripped whichever was typed; a leading `0` is replaced
      by `+232`; an existing `+232` is left as-is.
    - **Rejected:** anything that, after removing spaces, is not digits with at most a single
      leading `+` — a letter, a hyphen, brackets, a second `+`. Refused, never silently stripped:
      a character that does not belong in a phone number is more likely a typo than a formatting
      choice, and "fixing" it would store a number the person never gave. **Also rejected: a
      number of the wrong length even though every character was individually valid**
      (`07690000`, `+23276900005123`, `23276900005` with no `+`, a non-`+232` country code) —
      the case a per-character check alone lets through, and the one that would otherwise create
      an account that registered successfully and can never be signed into.
    - **One function, `PhoneNumber.normalize` (`com.bloodlink.common`), is the only implementation,
      and all three phone entry points call it: `AuthService.register` (before the duplicate check
      and before storing), `AuthService.login` (before the value is compared), and
      `DonorLookupService.lookupByPhone` (the number bank staff type before recording a donation).**
      Not three checks that agree today — one function, for the same reason Business Rule 2's
      callers all share `Donor.isEligibleToDonate()`. Two copies of this rule are two copies free
      to drift, and the failure drift produces is an account a real donor created and can never
      get back into. A donor who registers typing `076900005` must be able to log in typing
      `+232 76 900 005`, and the blood bank must be able to find them typing either.
    - **Refusal is a 400 keyed to the `phone` field**, not the vague 401 a failed login returns —
      including when it is a login that raised it. It describes the shape of what was typed, not
      whether any account exists, so it enumerates nothing; and answering "invalid phone number or
      password" to someone who typed seven digits would hide a fixable typo behind a complaint
      about a password that was never the problem.
    - **`V11` converted every phone already stored to this format**, so accounts created before
      the rule existed still work. It refuses to run if converting would make two accounts share
      one number (a question only a person can answer), and leaves anything it cannot convert
      untouched with a warning naming it rather than guessing at someone's number.
    - **Applies to `User.phone` only.** `BloodBank.contact_phone` and `Hospital.contact_phone` are
      display-only facility numbers — nothing compares them and nothing logs in with them — and
      are deliberately left as entered.
    - **Accounts written straight into the database must store the normalised form.** There is no
      self-registration path for HOSPITAL_STAFF, BLOOD_BANK_STAFF or ADMIN (Business Rules 8, 9),
      so those rows are inserted by hand; one stored in any other spelling cannot be signed into.

## Actors (roles) and what each can do

- **Donor** — view own profile/history/eligibility, respond to donation appeals
- **Hospital Staff** — submit blood requests, track their hospital's request status, view live inventory across blood banks (read-only)
- **Blood Bank/Lab Staff** — manage own bank's inventory, approve/reject/fulfil incoming requests, send donation appeals
- **Administrator** — manage all user accounts, view aggregate reports (donation trends, request outcomes)

## Conventions

- **At the end of every task, after listing files changed, commit and push as the closing step — not optional.** Run `git add`, then `git commit` with a clear, specific message describing what changed, then `git push`. A task is not finished until it is committed and pushed to `origin/main`. State in the summary that this was done, including the resulting commit hash.
- **`bloodlink_dev` holds real data. Testing must never delete or overwrite a row it did not itself create.**
  This is not a style preference — it was violated in Phase 6 and destroyed the project owner's manually
  entered stock batches, which were unrecoverable (no backup, `archive_mode` is off). The rules:
  - **Never write a `DELETE` whose `WHERE` clause selects by a shared attribute** (`blood_bank_id`,
    `hospital_id`, `status`, a date range). With only two blood banks and two hospitals seeded,
    `where blood_bank_id in (...)` is the entire table wearing a disguise. Delete **only** by primary keys
    captured from the test run's own inserts — use `insert ... returning id` and delete exactly those ids.
  - **Never use `on conflict ... do update`** in a test seed. It silently overwrites live rows and reports
    the same `INSERT 0 n` whether it inserted or clobbered, so the damage is invisible in the output.
    A test seed must use plain `insert` and fail loudly if the row already exists.
  - **Prefer not touching `bloodlink_dev` at all.** Create a throwaway database for the run
    (`createdb bloodlink_smoke`, point `spring.datasource.url` at it, `dropdb` afterwards). Flyway builds
    the schema from scratch, so this costs seconds and cannot damage anything.
  - **Take a `pg_dump` before any manual write to `bloodlink_dev`**, and say in the summary where it was saved.
  - **Never report "test data cleaned up" without evidence.** State the exact statements run and the row
    counts they affected, and confirm the deleted count equals the count inserted. If those two numbers
    disagree, something that was not yours was destroyed — say so immediately rather than reporting success.
  - **`bloodlink_test` is a third, deliberate exception to "prefer a fresh throwaway database" above** —
    it is a dedicated, *persistent* fixture for exactly one test, `BloodRequestLifecycleIT`, which is
    `@Transactional` and rolls back everything it writes, so re-running it never leaves residue to clean up.
    Create it once with `createdb bloodlink_test`; Flyway builds the schema on first use like anywhere else.
    Never point it at `bloodlink_dev`, and never use it for anything but that one test — a second,
    non-transactional consumer would break the "always safe to rerun" guarantee this convention exists for.
- **Explain what you did in plain, non-technical language in every summary**, in addition to the technical detail. Assume the project owner is not a professional software engineer. If a technical term is genuinely necessary (e.g. "race condition," "pessimistic lock," "BCrypt"), briefly define it in one plain sentence the first time it's used in that summary, rather than leaving it unexplained.
- **At the end of every task, list every file created or modified**, grouped as New / Modified, and for each new or changed method/function, give its name and a one-line purpose. This is a structured list in addition to any prose summary, not a replacement for it — the project owner reviews this list against `git status` before verifying anything in the browser.
- **Work directly on the `main` branch.** Do not create feature branches, task branches, or any other branch unless explicitly instructed to. This is a two-person project working phase-by-phase in turns; branching adds coordination overhead we've deliberately chosen not to use.
- **Comment all non-trivial code**, especially service-layer methods and business logic (eligibility checks, threshold calculations, request status transitions, WebSocket event triggers). A short comment above each method explaining *what it does and why*, not just what the code says. This matters for two people maintaining this together, and for explaining the implementation later.
- Configuration file: `application.properties` (not `.yml`) — chosen for this project, keep consistent across all phases
- REST endpoints: `/api/<resource>`, plural nouns, standard verbs (GET/POST/PATCH)
- All timestamps UTC
- All monetary/unit fields are integers (no fractional blood units)
- Every entity has a UUID primary key, not auto-increment integers
- Frontend fetches use the JWT from auth; role-gated UI must also be role-gated server-side — never trust the frontend alone for access control

## What NOT to do

- Don't invent additional entities or fields not listed above without asking
- Don't let the frontend design (colours, layout, copy) drift from the existing mockup components
- Don't build features out of dependency order — see the phased build plan for sequencing
