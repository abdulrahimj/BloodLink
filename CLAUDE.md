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
| REQUEST_STATUS_CHANGE | the raising hospital's HOSPITAL_STAFF | units arrive against a request, it is fulfilled, or it closes |
| REQUEST_PROGRESS | BLOOD_BANK_STAFF at every other bank still watching it | a bank contributes, changing the units outstanding (Business Rule 20) |

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
| email | varchar, unique — nullable since V13, but only for DONOR (DB check `users_email_required_for_staff`); "none" is always NULL, never `''` (`users_email_not_blank`). See Business Rule 18. |
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

Seeded, not self-registering: **Makeni Regional Blood Bank**, **Holy Spirit Blood Bank**. Further banks
are added only by an administrator (Business Rule 19). `name` is unique case-insensitively (V14, `uq_blood_banks_name`).

### Hospital
| field | type |
|---|---|
| id | UUID, PK |
| name | varchar |
| location | varchar |
| contact_phone | varchar |

Seeded, not self-registering: **Makeni Regional Hospital**, **Holy Spirit Hospital**. Further hospitals
are added only by an administrator (Business Rule 19). `name` is unique case-insensitively (V14, `uq_hospitals_name`).

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
| status | enum: PENDING, PARTIALLY_FULFILLED, FULFILLED, REJECTED — APPROVED removed in V15 (Business Rule 20); derived from the contributions, never set by hand |
| last_contributed_by_blood_bank_id | UUID, FK -> BloodBank, nullable — renamed from `fulfilled_by_blood_bank_id` in V15. Means "the bank that contributed most recently", a display convenience; BloodRequestContribution is the source of truth for who gave what. See Business Rule 20. |
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

### BloodRequestContribution
| field | type |
|---|---|
| id | UUID, PK |
| blood_request_id | UUID, FK -> BloodRequest, ON DELETE CASCADE |
| blood_bank_id | UUID, FK -> BloodBank, ON DELETE CASCADE |
| units_contributed | int — always > 0 (DB CHECK) |
| contributed_at | timestamp |

Added in V15 for Business Rule 20. One row per act of giving: **no unique constraint on
(request, bank)** — unlike BloodRequestDecline, a bank may contribute to the same request several
times as stock arrives. This table is the source of truth for how much of a request is covered and
by whom; `BloodRequest.status` and `last_contributed_by_blood_bank_id` are both derived from it.
There is deliberately no database-level cap on the sum per request — a CHECK cannot see other rows
and a trigger could not see an uncommitted concurrent one; the pessimistic row lock in
`RequestService` is the real guard. See Business Rule 20.

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
| type | enum: LOW_STOCK_ALERT, DONATION_APPEAL, NEW_REQUEST, REQUEST_STATUS_CHANGE, REQUEST_PROGRESS (added in V15) |
| is_read | boolean |
| created_at | timestamp |

### AuditLog
| field | type |
|---|---|
| id | UUID, PK |
| actor_user_id | UUID, FK -> User — the administrator who acted |
| action | enum: ACCOUNT_CREATED, ACCOUNT_EDITED, ACCOUNT_SUSPENDED, ACCOUNT_REACTIVATED, BLOOD_BANK_CREATED, HOSPITAL_CREATED (last two added in V14) |
| target_user_id | UUID, FK -> User, nullable since V14 — the account acted upon; set for the four ACCOUNT_ actions only |
| target_blood_bank_id | UUID, FK -> BloodBank, nullable — added in V14; set for BLOOD_BANK_CREATED only |
| target_hospital_id | UUID, FK -> Hospital, nullable — added in V14; set for HOSPITAL_CREATED only |
| details | text — what changed, in words, as of that moment; never a password |
| created_at | timestamp |

Added in V12 (table `audit_log`) for Business Rule 17. **Append-only**: no row is ever updated or
deleted. Enforced at four levels — no endpoint but GET, `AuditLogRepository` extends Spring Data's bare
`Repository` (not `JpaRepository`, which would bring `deleteAll`), the entity is Hibernate `@Immutable`
with no setters, and V12's triggers make UPDATE, DELETE and TRUNCATE fail in the database itself. Every
foreign key is ON DELETE NO ACTION, so a user or facility that appears in the log can never be deleted.
**Exactly one target per row, matching its action** — V14's `audit_log_target_matches_action` CHECK
replaces the NOT NULL it removed from `target_user_id`, so an account action can never point at nobody.

### Relationships
User 1–1 Donor · Donor 1–N Donation · BloodBank 1–N BloodInventory · BloodBank 1–N Donation ·
BloodBank 1–N BloodRequest (as fulfiller) · Hospital 1–N BloodRequest · User 1–N Notification ·
BloodRequest 1–N BloodRequestDecline · BloodBank 1–N BloodRequestDecline ·
BloodRequest 1–N BloodRequestContribution · BloodBank 1–N BloodRequestContribution ·
BloodBank 1–N AppealCooldown · User 1–N AuditLog (as actor) · User 1–N AuditLog (as target) ·
BloodBank 1–N AuditLog (as target) · Hospital 1–N AuditLog (as target)

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
   - **The Send Appeal dialog tells "nobody registered" apart from "everyone recovering".** The reach
     preview returns both `eligibleDonors` and `donorsInRecovery`; zero of each means no donor of that
     group is registered ("No donors of this blood type are registered yet"), while zero eligible with
     some recovering names how many are inside the 90-day window. The wording lives in
     `frontend/src/lib/appealReach.js` (`describeAppealReach`) and must never say everyone is eligible
     when there is nobody to appeal to.
3. **Stock status thresholds** (per blood group, per blood bank): below 15 units = Critical,
   below 40 units = Low, otherwise Sufficient. Confirm with project owner before changing these numbers.
4. **`Donation.blood_type` is independently recorded, not copied from `Donor.blood_type`.** Blood type
   is re-verified by testing at every donation; the donation record must reflect what was tested at
   that time, not silently follow later edits to the donor's profile.
5. **BloodRequest status lifecycle** — **superseded by Business Rule 20.** It now reads
   PENDING → PARTIALLY_FULFILLED → FULFILLED (or straight to FULFILLED), or PENDING → REJECTED;
   APPROVED no longer exists. What survives unchanged is the half that always mattered: units
   leaving a bank must decrement that bank's `BloodInventory.units_available`, in the same
   transaction as the record of them leaving.
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
10. **Approving a request also requires sufficient stock at that moment** — **superseded by
    Business Rule 20, and no longer applicable.** There is no approval step: a bank issues units when
    it acts, so the stock check and the deduction are the same moment. The concern behind this rule —
    a hospital stranded behind a bank that claimed its request and could not help — is now impossible
    rather than guarded against, because nothing claims a request exclusively any more.
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
    - **Business Rule 20 adds two things here and changes nothing else.** A bank that has contributed
      units may not then decline the same request (409) — the two are opposite answers to one
      question, and a decline counts towards closing a request that bank just helped fill. And a
      request that has received any units **never closes by decline**, however many banks decline the
      remainder; only an untouched (PENDING) request still closes when every bank has declined. The
      exclusive claim this rule's earlier wording referred to — one bank owning a request from
      approval onward — no longer exists.
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
      so a row inserted by hand in any other spelling cannot be signed into. `POST /api/admin/users`
      (Business Rule 14) normalises for itself, and is the reason hand-inserting one is now the
      exception rather than the only way.
    - **The demo seed follows this too.** V11 runs before any demo row exists, so it never saw the
      accounts `db/demo-seed` creates; `V906__demo_normalize_phone_numbers.sql` converts them (staff
      `+232 76 900 00x` lose their spaces; the generated donors are padded to 8 digits). Demo sign-in
      by phone, typed either way, was silently failing until it existed. **Any future migration that
      changes a table the demo seed writes to must be checked against a fresh demo database** — V15
      broke `V904` this way, because a fresh database runs every migration in version order and the
      seed files run last. Demo seed files are disposable, so editing one is acceptable (as V904 was),
      but it changes the checksum: existing demo databases must be dropped and recreated.
14. **An administrator creates staff and administrator accounts through `POST /api/admin/users`;
    the system generates the password and shows it exactly once.** This is the "created by an
    administrator" path Business Rules 8 and 9 always referred to. Before it, those rows were
    inserted into the database by hand — which meant hashing a password by hand, and getting it
    wrong silently at least once, surfacing only as an account that would not log in.
    - **Three roles, never DONOR.** HOSPITAL_STAFF, BLOOD_BANK_STAFF and ADMIN only. Donors
      self-register (Business Rule 8), and that path also writes the linked `Donor` row — blood
      group, date of birth, address — which this endpoint has no fields for; a DONOR `User` with
      no `Donor` row would break every donor screen. The Admin Console does not offer the option
      and the server refuses it anyway: an option a form does not show is a courtesy, not a control.
    - **The facility is required or forbidden by role, and never merely ignored.** `hospital_id` is
      required for HOSPITAL_STAFF and forbidden otherwise; `blood_bank_id` is required for
      BLOOD_BANK_STAFF and forbidden otherwise; an ADMIN carries neither, because an administrator's
      authority is citywide. Sending the wrong one is a 400 rather than a silently dropped field —
      accepting it would accept a request whose author believed something untrue about what they
      were creating. A staff account with no facility can see nothing, so the missing case is
      equally refused. A facility id matching no row is a 404: the request is well-formed, and the
      thing it named does not exist.
    - **The password is generated, never chosen, and readable exactly once.** `TemporaryPasswordGenerator`
      draws 12 characters from a `SecureRandom` over an alphabet with the look-alikes removed
      (no `O`/`0`, no `I`/`l`/`1`, no punctuation) because a human reads this password aloud to
      another human. Only the BCrypt hash is stored. The plaintext is returned in the creation
      response and then it is gone — no endpoint can return it again, and nothing stores or logs it.
      The Admin Console's confirmation screen says so plainly because it is true of the system, not
      because the interface is being cautious: **if the password is lost, the account must be
      created again.** An administrator relays it directly; BloodLink sends no email or SMS.
    - **Phone goes through Business Rule 13's shared `PhoneNumber.normalize`** and is refused as a
      duplicate exactly as registration refuses one (409), compared against the normalised value so
      the same number typed two ways cannot become two accounts. Email is lower-cased and likewise
      unique.
    - **Every account is created ACTIVE**, so it works the moment the password is handed over.
    - **Admin-only, enforced by SecurityConfig's `/api/admin/**` rule** — a donor, hospital-staff or
      blood-bank-staff token is refused with a 403 in the filter chain, before the service is
      reached, and an unauthenticated caller gets a 401.
15. **The application refuses to start on the public placeholder JWT secret, except on a profile
    that never leaves a developer's own machine.** `bloodlink.jwt.secret` resolves as
    `${BLOODLINK_JWT_SECRET:local-dev-only-change-me-0123456789abcdef}`. That default is committed to
    the repository, so anyone can read it — and anyone who knows the signing secret can forge a login
    token for any account, administrators included. HMAC-SHA256's 32-character minimum was the only
    check before this rule, and the placeholder (41 characters) passes it, so a deployment that forgot
    to set the variable used to start silently and accept forged logins.
    - **Tolerated, with a warning:** the default profile (none active, or `default` named explicitly)
      and `demo`. Those are the local-only profiles that exist: `mvnw spring-boot:run` against
      `bloodlink_dev`, and docker-compose's container-only demo. Even there, a framed, upper-case WARN
      banner is printed at startup saying the secret is a public placeholder for local development only.
    - **Refused, hard, under every other profile** — `prod`, `staging`, a profile nobody has invented
      yet, and any mix such as `demo,prod` (every active profile must be local-only, not one of them).
      The allowed list names what is known to be safe; nothing names what is dangerous, so a newly
      added profile is protected without anyone remembering to protect it. The start fails with
      `InsecureJwtSecretException`, reported by `InsecureJwtSecretFailureAnalyzer` as Spring Boot's
      "APPLICATION FAILED TO START" block naming the problem and `BLOODLINK_JWT_SECRET` as the fix.
    - **`test` is deliberately not exempt.** It exists only as `@ActiveProfiles("test")` on the
      integration tests, and `application-test.properties` sets its own test-only secret instead. That
      file lives in `src/test/resources` and is never in the jar, so an exemption keyed on the profile
      *name* would let a server started with `SPRING_PROFILES_ACTIVE=test` run on the placeholder.
    - **Owned by `JwtSecretPolicy`, applied in `JwtService`'s constructor** — the one place the secret
      becomes a signing key, so nothing can sign with the placeholder on a real deployment. `JwtService`
      is `@Lazy(false)` so the check stays a *startup* check even if lazy initialisation is enabled.
      The placeholder text is duplicated in `application.properties.example` and `.env.example`;
      `JwtSecretPolicyTest` fails the build if either drifts from `JwtSecretPolicy.PUBLIC_PLACEHOLDER`,
      since a mismatch would silently switch the check off.
    - **Docker gets the secret from the environment, never from a file baked into the image.**
      `docker-compose.yml` passes `BLOODLINK_JWT_SECRET` from a gitignored `.env` (template: the
      committed `.env.example`) and refuses to start if it is unset. The Dockerfile builds from the
      committed `application.properties.example`, and `.dockerignore` keeps a developer's local
      `application.properties` out of the build — it holds their credentials, and a fresh clone does
      not have one, which previously made the image fail at startup on every machine but its author's.
16. **Every signed-in user manages their own name, email and password from Profile Settings — and a
    password change requires the current password.** One shared screen
    (`frontend/src/components/ProfileSettings.jsx`) for all four roles, opened from each portal's
    Settings sidebar link; one set of endpoints behind it, `/api/users/me`, open to any valid token and
    acting only on the caller's own account (the id comes from the token — the URL cannot name anyone
    else). Before this rule there was no way to change a password after registration at all.
    - **Editable, by every role including DONOR and ADMIN:** `full_name` and `email`
      (`PATCH /api/users/me`), normalised as registration does (trimmed; email lower-cased) and still
      unique — a clash with another account is a 409, re-saving your own is not. And the password
      (`PATCH /api/users/me/password`).
    - **Not editable by the account holder, for any role — ADMIN included:** `phone` (the login
      credential, Business Rule 13), `role`, `blood_bank_id` / `hospital_id` (Business Rules 8, 9) and
      `status`. The screen shows them read-only. A request that carries any of them — or any property
      the endpoint does not accept — is **refused whole with a 400 naming the field**, never partly
      applied and never silently ignored with a 200. The payload records capture unrecognised
      properties with `@JsonAnySetter` precisely so they can be refused, because the JSON mapper would
      otherwise drop them and report success. Note that no endpoint edits a phone, role or facility
      after an account exists, for anyone: today those are database changes, so the screen says they
      "cannot be changed here" rather than pointing at an in-app fix that does not exist.
    - **A password change must prove the current password**, checked with `PasswordEncoder.matches`
      against the stored BCrypt hash — the same comparison login uses. A session left open on a shared
      machine must not be enough to set a new password and lock the owner out. Wrong current password:
      **401 with a field error on `currentPassword`** and nothing written. The new password follows
      registration's rule (8–72 characters), and one identical to the current password is refused (400)
      rather than "succeeding" without changing anything. Name and email need no current password:
      neither is a credential.
    - **That 401 must not sign the user out.** `frontend/src/api/client.js` discards the session on a
      401 because that normally means the token is dead. A 401 that carries field errors is instead the
      server rejecting something the user typed, and keeps the session; session-level 401s
      (`RestAuthenticationEntryPoint`) never carry field errors, which is what makes the distinction
      reliable. Any future endpoint that answers 401 about a typed credential must name the field too.
    - **Known limits, not handled here:** tokens issued before a password change stay valid until they
      expire (JWTs are stateless — the 24h lifetime is the bound), and the current-password check is
      not rate-limited, the same as login.
17. **An administrator may change another account's phone and facility, and every administrator action
    on an account is recorded in a permanent, append-only audit log.**
    - **Editing: `PATCH /api/admin/users/{id}/details`**, admin-only (SecurityConfig's `/api/admin/**`).
      Phone goes through Business Rule 13's `PhoneNumber.normalize` and the same duplicate check as
      registration (409, compared normalised; keeping the account's own number is not a clash). Facility
      follows Business Rule 14's required/forbidden rule — one shared method,
      `AdminUserService.assignFacility`, used by both creation and editing — judged against the role the
      account **already has**. The request always carries the full desired state (phone, plus the
      facility the role requires).
    - **Not editable here:** role (deliberately out of scope — a DONOR has a linked Donor row a role
      change would affect, which needs its own decision; a `role` in the request is refused with a 400
      saying so), and anything else (name, email, status) via the same capture-and-refuse approach as
      Business Rule 16. **Nor the caller's own account** (409): Business Rule 16 already keeps every
      account holder, admins included, from changing their own phone, and this must not be a side door.
    - **What is logged — the four actions, from day one:** ACCOUNT_CREATED (Business Rule 14's create),
      ACCOUNT_EDITED, ACCOUNT_SUSPENDED and ACCOUNT_REACTIVATED (the existing status PATCH) — each with
      the acting admin, the affected account, a human-readable `details` snapshot ("Phone changed from
      +232… to +232…. Blood bank changed from X to Y.") and the time. Only real changes are logged: a
      status set to what it already was, or an edit that changes nothing, records nothing. The generated
      password (Business Rule 14) never appears in an entry. Reads are not logged.
    - **Same transaction as the action.** `AuditLogService.record` is `Propagation.MANDATORY`: it refuses
      to run without the caller's transaction, so an action and its entry commit or roll back together —
      the log can never show a change that did not happen, or miss one that did.
    - **Append-only, at four levels** — see the AuditLog schema section. Read with
      `GET /api/admin/audit-log` (one page of entries, newest first); the Admin Console's Audit Log page
      shows it with no row actions. Any other method on that path is a 405 and a single entry has no
      route (404).
    - **Paged, not returned whole.** The log only grows, so the original "return the whole thing" design
      was always going to need this eventually. `GET /api/admin/audit-log` takes two optional query
      parameters: `page` (0-based, default 0) and `size` (default 20, maximum 100). `page` cannot be
      negative and `size` must be between 1 and 100 — either violation, or a non-numeric value, is a 400
      naming the field; nothing is silently corrected. The response is
      `{ "content": [...entries...], "page": n, "size": n, "totalElements": n, "totalPages": n }`, not
      Spring Data's own `Page` shape. Newest-first ordering is unchanged, with a tie-breaker added
      (`created_at desc, id desc`) so two entries sharing a timestamp can never swap places between one
      page request and the next — without it, a row could be skipped or duplicated across a page
      boundary. `AuditLogRepository` stays a bare `Repository` with a single `Pageable`-taking finder;
      no delete or save-update method was added, and the V12 triggers and the `@Immutable` entity are
      untouched. No migration was needed — this is a read-path change only.
    - **Known limits:** a database superuser could still drop the triggers deliberately — nothing inside
      PostgreSQL can stop its own owner; the triggers stop every accidental or application-level change.
      A moved staff member's server-side access follows the new facility on their next request, but their
      browser shows the old facility until they sign in again.
18. **Email is optional for donors, format-checked when given, and unique when present. Staff and
    administrator accounts still require one, exactly as before.** Many donors have a phone but no email,
    and phone is the login (Business Rule 13), so requiring an email turned some away for no reason.
    - **Blank means none, stored as NULL.** `EmailAddress.normalizeOptional` (`backend/.../common/`) is the
      one shared function — the same pattern as `PhoneNumber.normalize`: null/blank → `null`; anything else
      is trimmed, lower-cased and must match `name@domain.tld` (dotted domain, 2–24 letter ending; no
      leading/trailing/double dots; ≤ 64 before the `@`, ≤ 254 overall). A bad address is a 400 on the
      `email` field. Deliberately stricter than Bean Validation's `@Email`, which accepts `name@gmail`.
    - **Format only — never a network check.** No DNS or deliverability lookup: that would put a network
      call inside registration, against this system's requirement to work over intermittent connections.
      Known limit: addresses in non-Latin scripts are refused (the donor can register without one).
    - **Unique when present:** a clash is a 409 with the message **"This email is already registered"**,
      compared after lower-casing. The duplicate check is skipped entirely when there is no email.
    - **Two donors with no email never collide — by standard SQL NULL semantics, not custom code.** V1's
      `users_email_key` is a plain `UNIQUE (email)`, which in PostgreSQL is NULLS DISTINCT: NULL never
      equals NULL, so any number of rows may have none while two real addresses still cannot match. Do
      not change it to NULLS NOT DISTINCT. Because two `''` values *would* collide, V13 forbids storing
      an empty string at all (`users_email_not_blank`), and requires an email for every non-DONOR role
      (`users_email_required_for_staff`).
    - **Where it applies:** donor registration (`POST /api/auth/register`) and a donor's own Profile
      Settings (Business Rule 16) — a donor may leave the email blank there too (keeping or clearing it),
      with the same format check. For every other role Profile Settings still requires one. Staff and
      administrator creation (Business Rule 14, `CreateStaffAccountPayload`) is **unchanged**:
      `@NotBlank @Email`, and its own duplicate message.
    - **Frontend:** the Register form marks email "(optional)" and sends blank as `null`; Profile Settings
      shows "(optional)" for donors and treats a null email as empty (it previously assumed one existed).
19. **An administrator adds new blood banks and hospitals from the Admin Console — create only.**
    `POST /api/admin/blood-banks` and `POST /api/admin/hospitals` (`AdminFacilityController` /
    `AdminFacilityService`), each taking `{ name, location, contactPhone }` and returning 201 with the new
    row in the same shape as `GET /api/blood-banks` / `GET /api/hospitals`.
    - **Create only — no edit, no delete.** Changing or removing an existing facility means deciding what
      happens to the staff, stock, requests and donations tied to it; that is a deliberately separate,
      later decision. No PATCH or DELETE route exists for either.
    - **Admin-only**, by SecurityConfig's existing `/api/admin/**` rule: any other role's token is a 403 in
      the filter chain, no token a 401.
    - **Name is unique within its type, case-insensitively.** V14 adds unique indexes on `lower(name)`
      (`uq_blood_banks_name`, `uq_hospitals_name`); before storing, the service trims the name and collapses
      runs of spaces (`AdminFacilityService.normaliseName`), so "holy  spirit BLOOD bank" is the same bank.
      A clash is a **409 "A blood bank named "X" already exists."** (or "A hospital named …"), checked up front
      and also translated from the index's violation if two administrators race. A hospital *may* share a
      name with a blood bank — Holy Spirit is both. V14 refuses to run, naming the rows, if duplicates already
      exist, rather than guessing which one is real.
    - **`contactPhone` is only required, never normalised.** It is a display-only facility number (Business
      Rule 13 already excludes `contact_phone`), and a front-desk line may be a landline or carry an extension.
      All three fields are `@NotBlank`, sized to their V1 columns (255 / 255 / 32).
    - **A new blood bank needs no inventory seeding.** No `BloodInventory` rows are written:
      `InventoryService.listInventory` already reports all eight groups for every bank, filling a missing
      row as 0 units — Critical under Business Rule 3 — and the first "Add New Batch" or recorded donation
      creates the real row.
    - **Audited like Business Rule 17's actions**: `BLOOD_BANK_CREATED` / `HOSPITAL_CREATED`, in the same
      transaction (`AuditLogService.recordBloodBankCreated` / `recordHospitalCreated`, both
      `Propagation.MANDATORY`), naming the administrator, pointing at the new facility through
      `target_blood_bank_id` / `target_hospital_id`, with details such as "Created blood bank X, location Y,
      contact phone Z." The Audit Log page's "Subject" column labels these "Blood bank" / "Hospital" instead of
      a role (`AuditLogEntryResponse.targetType`).
    - **Frontend:** the Admin Console's Blood Banks and Hospitals sidebar links (previously inert) open one
      shared page, `FacilitiesView`: a "Create New" button with an inline form at the top, and that type's full
      list below, read from the existing public listing endpoints. No search, filtering or paging — two of each
      today. A new facility joins the list the console already holds, so the Partner Facilities card and the
      staff-account pickers pick it up without a reload.
20. **A blood request is filled cooperatively: any bank with stock contributes units towards it until
    it is covered.** This replaces the single-bank model, in which one bank approved a request —
    claiming it exclusively — and later fulfilled it in one all-or-nothing step. A request for 4 units
    that no single bank could cover could not be filled at all, even when two banks holding 2 units
    each could have filled it together. Schema: `V15`.
    - **Lifecycle:** PENDING → PARTIALLY_FULFILLED (units received, some still outstanding) →
      FULFILLED, or straight to FULFILLED when one contribution covers the whole amount; or
      PENDING → REJECTED exactly as Business Rule 11 already describes. **Status is derived from
      the arithmetic after every contribution, never set by hand**, which is why there is no
      "mark fulfilled" action anywhere.
    - **APPROVED is gone.** It meant "a bank has claimed this and will fill it later", and the claim
      was the very thing that stopped a second bank helping. `V15` converts any existing APPROVED
      request back to PENDING with its claiming bank cleared (nothing had been issued against it),
      and converts every FULFILLED one into a single contribution of its full size so past
      fulfilments survive in the new table.
    - **One action, `CONTRIBUTE`, replacing Approve and Fulfil** (`PATCH /api/requests/{id}` with
      `units`). It deducts the acting bank's stock immediately, writes a `BloodRequestContribution`
      row, and updates the status — all in one transaction, so units can never leave stock without
      being recorded or be recorded without leaving stock. `REJECT` is unchanged (Business Rule 11).
    - **Units are capped at what is still outstanding, not refused.** Between the operator reading
      the screen and pressing the button another bank may have covered part of it; that is not their
      mistake to be punished for, so the server records what is genuinely wanted and reports it back.
      **More units than the bank actually holds is refused** (409 naming both figures) — that is a
      mistake about its own shelf, and silently issuing fewer than typed would leave the operator
      believing they had sent more.
    - **`fulfilled_by_blood_bank_id` was repurposed, not dropped** — renamed to
      `last_contributed_by_blood_bank_id`, meaning "the bank that contributed most recently". A single
      column cannot name several contributors, so leaving the old name would assert something untrue;
      dropping it would lose a useful pointer for the one-name display case and the notification
      wording. **`BloodRequestContribution` is the source of truth** for who gave what and how much.
    - **A request with units outstanding stays visible and actionable to every bank** (PENDING and
      PARTIALLY_FULFILLED alike), which reverses the old "disappears from other queues on approval"
      behaviour. A bank also keeps any request it has contributed to, closed or not, as its own
      history; a request another bank completed without it never appears.
      `BloodRequestRepository.findActionableForBank` is the one place this is expressed.
    - **A bank that contributes is done with declining, and a bank that declines is done
      contributing.** Both are refused with a 409. Known limit: a bank that declined and later
      receives stock cannot contribute to that request — its decline was its final answer, and the
      request is not in its queue to act on anyway. Revisit only if it happens in practice.
    - **A partially fulfilled request never auto-closes**, however many banks decline the remainder —
      deliberately different from an untouched one (Business Rule 11). Real progress exists, the ward
      has blood it can use, and stock that completes it may arrive tomorrow. Doubly ensured: a
      contributor cannot decline, so a request with contributions can never collect a full set of
      declines, and `RequestService.reject` closes only a PENDING request regardless.
    - **Concurrency: the pessimistic row lock on the request is the whole guarantee.** Both banks see
      every open request, so two contributing at the same instant is ordinary rather than rare.
      `findByIdForUpdate` locks the request row *before* the contributed total is read, so the second
      transaction waits and then reads a total that includes the first — and its units are capped
      against what is genuinely left. Without it both would read "4 needed", both would deduct 4, and
      a hospital that asked for 4 units would be sent 8. `PartialFulfilmentRaceIT` proves this with
      two real threads, two real transactions, and its own throwaway database.
    - **`REQUEST_PROGRESS`, a fifth notification type**, goes to every *other* bank still watching a
      request whenever its outstanding figure changes — not the contributing bank (it has the answer
      in its own response) and not a bank that has declined (it can no longer act). Without it two
      banks can each set out to cover the same shrinking gap. The hospital hears separately, as
      REQUEST_STATUS_CHANGE, with the figures ("2 of 4 units received, 2 units still needed").
    - **Units Issued** on the Inventory screen now sums this bank's own contributions by
      `contributed_at`, rather than the full size of requests it fulfilled by their creation date —
      more accurate on both counts once a request can be split between banks.
    - **Frontend:** the Blood Bank Dashboard's row carries a units box (capped at the smaller of
      outstanding and this bank's stock) plus Contribute and Decline; both portals show progress
      ("2 of 4 units received · 2 still needed · from …") instead of a binary badge. The dashboard's
      stat card becomes "Open Requests" (PENDING + PARTIALLY_FULFILLED), and the Admin Console's
      request-outcomes donut shows "Partly filled" where "Approved" used to be.
21. **A blood bank's "donor roster" means donors with at least one donation recorded at that
    bank — there is no other link between a donor and a bank in the schema, since donors
    register on their own.** This is the same reading `DonationRepository.countDistinctDonorsByBloodBankId`
    already gave the Dashboard's "Registered Donors" stat; the Donors page (previously a dead
    sidebar link) is the full version of that same question.
    - **`GET /api/donors/roster`** returns one page of that bank's donors — paginated exactly
      like the Audit Log's pagination (`page` 0-based default 0, `size` default 20 max 100, bad
      values refused with a 400 naming the field, never corrected), with optional `q` (matches
      name or phone), `bloodType`, `eligibility` (`ELIGIBLE` or `IN_RECOVERY`) and `sort`
      (`lastDonation`, the default, or `name`) — and a stable `donorId` tie-breaker so two donors
      sharing a sort value never swap pages. **`GET /api/donors/roster/{donorId}/donations`**
      returns that one donor's donation history at this bank only, newest first, capped at 50; a
      donor with no donation at this bank is a 404 — the same 404 as an id that matches nobody
      at all, so a bank can never learn that someone is a registered donor elsewhere.
    - **Exposes exactly: donor id, full name, blood group, phone, last donation date (any bank,
      labelled as such), eligibility, the date eligibility begins (null when already eligible),
      and donationsHere (this bank's own count).** Deliberately excludes address, email and date
      of birth — data minimisation: this is a bank employee looking at someone else's details,
      the same principle the Record Donation lookup (`DonorLookupResponse`) already states, and
      none of those three fields is needed to decide anything on this screen.
    - **Eligibility is never recalculated for this feature.** Both the response field and the
      `eligibility` filter are built from `Donor.getEligibilityStatus()`/`eligibleFrom()`
      (Business Rule 2) — the filter borrows `Donor.ELIGIBILITY_WINDOW_DAYS` to compute a cutoff
      *date* to compare against in the database (needed so pagination can filter before cutting
      the page), but the 90-day rule itself is evaluated in exactly the one place it always was.
    - **`BLOOD_BANK_STAFF` only, via `ActingBankResolver`** — bank staff act as their own bank
      and an ADMIN must name one (`bloodBankId`); a donor, hospital staff or unauthenticated
      caller is refused (403/403/401), the same pattern every other bank-scoped endpoint uses.
    - **Search does not go through `PhoneNumber.normalize`.** That function validates a complete
      number and would reject a deliberate partial search ("900", "076900"); instead a leading
      "0" is rewritten to "+232" (Business Rule 13's local-form mapping) and the rest is matched
      as a substring against the stored number.
    - **An index, `idx_donations_bank_donor` on `donations (blood_bank_id, donor_id)` (`V16`),
      backs both of this feature's queries** — `donations` had no index on either foreign-key
      column before this, which only mattered once a query started filtering by them.

## Pending documentation updates (dissertation chapters — not acted on here)

The code and this file are the source of truth; these are the places the written chapters have
fallen behind and need a pass of their own. Nothing here is a task for a coding session — they are
listed so nothing is forgotten when the chapters are next revised.

- **Chapters 3, 4 and 5 — ERD.** Add `BloodRequestContribution` (Business Rule 20, V15) and its two
  relationships (BloodRequest 1–N, BloodBank 1–N). On `BloodRequest`, rename
  `fulfilled_by_blood_bank_id` to `last_contributed_by_blood_bank_id` and change its meaning to
  "most recent contributor", and update the status enum: PARTIALLY_FULFILLED in, APPROVED out.
- **Chapters 3, 4 and 5 — request lifecycle narrative and Figure 3.5.** The approve-then-fulfil
  sequence is gone; a request is now filled by one or more contributions (Business Rule 20).
- **Chapter 3/4 — notification types.** A fifth type, REQUEST_PROGRESS, exists (V15).
- **Chapters 3, 4 and 5 — ERD and narrative for facilities.** Blood banks and hospitals are no
  longer purely seeded: an administrator creates them (Business Rule 19, V14), names are unique per
  type, and `audit_log` gained `target_blood_bank_id` / `target_hospital_id` with two new actions.

## Actors (roles) and what each can do

- **Donor** — view own profile/history/eligibility, respond to donation appeals
- **Hospital Staff** — submit blood requests, track their hospital's requests including how many units have arrived and from which banks (Business Rule 20), view live inventory across blood banks (read-only)
- **Blood Bank/Lab Staff** — manage own bank's inventory, contribute units to incoming requests or decline them (Business Rule 20), send donation appeals, view the roster of donors who have given at their own bank and each one's donation history there (Business Rule 21)
- **Administrator** — create staff and administrator accounts (Business Rule 14), add new blood banks and
  hospitals (Business Rule 19), manage all user accounts (edit another account's phone and facility, activate/suspend), view the audit log of administrator actions (Business Rule 17), view aggregate reports (donation trends, request outcomes)
- **Every role** — edit own name, email and password from Profile Settings (Business Rule 16)

## Conventions

- **Frontend logic worth testing goes in a plain `.js` file under `frontend/src/lib/` with a
  `*.test.js` beside it, run by `npm test`** (Node's built-in test runner — no test dependency added,
  keeping the fixed tech stack). Component wording or rules that only exist inside a `.jsx` file cannot
  be tested this way, so extract the function first (as `appealReach.js` was).
- **Every commit is authored and committed solely by Abdulrahim Jalloh <abdulrahim.jalloh.1710@gmail.com>, and commit messages carry no `Co-Authored-By:` or "Generated with Claude Code" lines — no exceptions.** This overrides any default attribution a tool would otherwise add. The history was rewritten to this standard on 2026-09-21 with `git filter-repo`. On the owner's machine it is also enforced by local hooks in `.git/hooks/` (`pre-commit` refuses any other author or committer; `commit-msg` strips those lines) and `user.useConfigOnly=true`; those are not versioned, so on any other clone this line is the rule.
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
    it is a dedicated, *persistent* fixture for the integration tests in `com.bloodlink.integration`
    (`BloodRequestLifecycleIT`, `AdminAccountCreationIT`, `AccountSettingsIT`, `AdminEditAndAuditLogIT`,
    `DonorOptionalEmailIT`, `AdminFacilityCreationIT`, `PartialFulfilmentIT`), every one of which is `@Transactional` and rolls back everything it writes,
    so re-running them never leaves residue to clean up.
    Create it once with `createdb bloodlink_test`; Flyway builds the schema on first use like anywhere else.
    Never point it at `bloodlink_dev`, and **never add a consumer that is not `@Transactional`** — that,
    not the number of test classes, is what would break the "always safe to rerun" guarantee this
    convention exists for. A test that genuinely cannot roll back belongs on a database of its own:
    `PartialFulfilmentRaceIT` (Business Rule 20) needs two real committed transactions to race, so it
    creates `bloodlink_race_test` before its Spring context starts and drops it afterwards, touching
    nothing shared. That is the pattern for any future test in the same position. (Originally worded as "exactly one test, `BloodRequestLifecycleIT`";
    widened deliberately when `AdminAccountCreationIT` was added, since the guarantee comes from the
    rollback, not from there being a single class.)
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
