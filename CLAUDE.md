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

REST is synchronous (requests, CRUD). WebSocket is one-directional server→client push, carrying every
Notification type and nothing else — there are no `@MessageMapping` handlers and no
client→server messages (a donor's acceptance of an appeal is a REST call, Business Rule 25):

| type | pushed to | raised when |
|---|---|---|
| LOW_STOCK_ALERT | that bank's BLOOD_BANK_STAFF | a stock change drops a group into Low or Critical |
| DONATION_APPEAL | **eligible** donors (Business Rule 2) whose `Donor.blood_type` matches | a blood bank sends an appeal (Business Rule 1) |
| DONATION_APPEAL_ACCEPTED | BLOOD_BANK_STAFF of the bank that sent the appeal | a donor accepts that appeal (Business Rule 25) |
| NEW_REQUEST | BLOOD_BANK_STAFF at banks that could fulfil it | a hospital submits a request |
| REQUEST_STATUS_CHANGE | the raising hospital's HOSPITAL_STAFF | units arrive against a request, it is fulfilled, or it closes |
| REQUEST_PROGRESS | BLOOD_BANK_STAFF at every other bank still watching it | a bank contributes, changing the units outstanding (Business Rule 20) |
| BATCH_EXPIRY_REMINDER | that bank's **active** BLOOD_BANK_STAFF | the daily job finds batches 7 days from expiry (Business Rule 26) |
| BATCH_EXPIRED | that bank's **active** BLOOD_BANK_STAFF | the daily job finds expired batches still holding units, every day until written off (Business Rule 26) |

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

> **In progress — batch-level stock redesign, Stage 3 of 6 done:** V19 cut stock over to batches (Business Rule 24) and every screen, rule, alert and report now reads usable batch stock; Stage 3 added the write-off action (`POST /api/inventory/{bank}/batches/{batch}/write-offs`, backend only) with its `STOCK_WRITTEN_OFF` audit entries; Stages 3b and 3b-2 made every stock change — Add New Batch, write-off, Record Donation, CONTRIBUTE — own-bank active staff only (ADMIN read-only, Business Rule 8). Still to come: Stage 4 the frontend (including the write-off screen), Stage 5 README/Postman/chapter notes, Stage 6 V24 dropping `blood_inventory_pre_batches` (V20–V22 are the appeal-acceptance migrations, Business Rule 25; V23 the expiry notice types, Business Rule 26).

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

### BloodInventoryBatch
| field | type |
|---|---|
| id | UUID, PK |
| blood_bank_id | UUID, FK -> BloodBank |
| blood_type | enum (as above) |
| units_received | int — > 0 (DB CHECK); fixed once the batch exists |
| units_remaining | int — between 0 and units_received (DB CHECK); falls as units are issued or written off |
| expiry_date | date — the last usable day, inclusive; fixed once the batch exists |
| received_at | timestamp |
| last_updated | timestamp |
| donation_id | UUID, FK -> Donation, nullable, unique — set exactly when source is DONATION |
| source | enum: DONATION, MANUAL (Add New Batch), MIGRATED (converted by V19) |
| expiry_noticed_at | timestamp, nullable — when the seven-day expiry reminder named this batch (Business Rule 26), so it is sent once; V19 also stamped batches already expired at the cutover (they still get the daily expired notice). Set only by a bulk update of this one column, so `last_updated` is untouched |

Table `blood_inventory_batches` (V18, the only record of stock since V19) — **one row per batch, each
with its own expiry date** (Business Rule 24). Usable stock is never stored: it is the sum of
`units_remaining` over a bank's unexpired batches of a group, computed on read. A V18 trigger refuses
any change to the identity columns (bank, group, units received, expiry, received_at, donation, source,
id): FEFO ordering relies on expiry never moving, and a wrong batch is corrected by writing it off
(`DATA_ENTRY_ERROR`) and re-adding it. Batches are never deleted; a used-up one stays as history with
0 remaining. One index beyond the keys: `idx_batches_fefo (blood_bank_id, blood_type, expiry_date, id)
where units_remaining > 0`, serving the FEFO lock, the usable sums and the batch list. Foreign keys are
NO ACTION throughout. **The stock identity** — `units_received = units_remaining + Σ allocated + Σ written
off` — holds for every batch; `BloodInventoryBatchRepository.findIdsBreakingStockIdentity` is the
check (native SQL, pasteable into psql).

The old one-row-per-(bank, group) `blood_inventory` table was renamed by V19 to
`blood_inventory_pre_batches` (its constraint and index names too), is read and written by nothing, and
is dropped by V24 (Stage 6; renumbered from V20 when Business Rule 25 took V20–V22, then from V23 when
Business Rule 26 took V23).

### ContributionAllocation
| field | type |
|---|---|
| id | UUID, PK |
| contribution_id | UUID, FK -> BloodRequestContribution (NO ACTION) |
| batch_id | UUID, FK -> BloodInventoryBatch (NO ACTION) |
| units_allocated | int — > 0 (DB CHECK) |

Table `contribution_allocations` (V18): which batches each contribution's units came from —
traceability, one row per batch drawn on, unique on (contribution, batch)
(`uq_allocation_contribution_batch`, which also serves lookups by contribution), plus
`idx_allocations_batch` for "where did this batch go". The allocations of a contribution add up to its
`units_contributed`: enforced in code (`BatchStockService.recordAllocations` refuses otherwise) and
checked by `ContributionAllocationRepository.findContributionIdsWithMismatchedAllocations`. Contributions
made before V19 have no allocations (their units left stock before batches existed). **Append-only**
like the audit log: no setters, `@Immutable`, a bare `Repository`, and V18 triggers refusing UPDATE,
DELETE and TRUNCATE (including a TRUNCATE reaching it by CASCADE).

### BatchWriteOff
| field | type |
|---|---|
| id | UUID, PK |
| batch_id | UUID, FK -> BloodInventoryBatch |
| units | int — > 0 (DB CHECK) |
| reason | enum: EXPIRED, DAMAGED, FAILED_TESTING, DATA_ENTRY_ERROR, OTHER |
| note | text, nullable — never blank (stored as NULL), ≤ 500 characters, required for OTHER (DB CHECKs) |
| written_off_by_user_id | UUID, FK -> User |
| written_off_at | timestamp |

Table `batch_write_offs` (V18): units discarded, why and by whom — the discard ledger. Append-only by the
same four means as ContributionAllocation. Written only by the write-off action
(`BatchWriteOffService`, Business Rule 24), together with the batch's deduction and its STOCK_WRITTEN_OFF
audit entry in one transaction; read by the batch history endpoint.

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
| type | enum: LOW_STOCK_ALERT, DONATION_APPEAL, DONATION_APPEAL_ACCEPTED (added in V21), NEW_REQUEST, REQUEST_STATUS_CHANGE, REQUEST_PROGRESS (added in V15), BATCH_EXPIRY_REMINDER and BATCH_EXPIRED (added in V23, Business Rule 26) |
| is_read | boolean |
| created_at | timestamp |
| accepted | boolean, default false — added in V20; true once the donor has accepted this DONATION_APPEAL (Business Rule 25) |
| source_blood_bank_id | UUID, FK -> BloodBank, nullable — added in V21; set on DONATION_APPEAL rows (the bank that sent the appeal) and on BATCH_EXPIRY_REMINDER / BATCH_EXPIRED rows (the bank the batches belong to, Business Rule 26) |
| blood_type | enum (as above), nullable — added in V21; set on DONATION_APPEAL rows only: the group appealed for |
| acceptance_alert_sent | boolean, default false — added in V22; true once the sending bank's staff have been told of this row's acceptance |

V21 back-filled `source_blood_bank_id` / `blood_type` on appeals sent before it by parsing the fixed
message template ("<bank> needs <group> donors. …"); later appeals are stamped when sent. Index
`idx_notifications_appeal_bank_type (source_blood_bank_id, blood_type, created_at) where type = 'DONATION_APPEAL'`.

### AuditLog
| field | type |
|---|---|
| id | UUID, PK |
| actor_user_id | UUID, FK -> User — the administrator who acted (for STOCK_WRITTEN_OFF, the blood bank staff member who discarded the units — never an administrator, Stage 3b) |
| action | enum: ACCOUNT_CREATED, ACCOUNT_EDITED, ACCOUNT_SUSPENDED, ACCOUNT_REACTIVATED, BLOOD_BANK_CREATED, HOSPITAL_CREATED (added in V14), STOCK_WRITTEN_OFF (added in V18; written by the write-off action since Stage 3) |
| target_user_id | UUID, FK -> User, nullable since V14 — the account acted upon; set for the four ACCOUNT_ actions only |
| target_blood_bank_id | UUID, FK -> BloodBank, nullable — added in V14; set for BLOOD_BANK_CREATED only |
| target_hospital_id | UUID, FK -> Hospital, nullable — added in V14; set for HOSPITAL_CREATED only |
| target_batch_id | UUID, FK -> BloodInventoryBatch, nullable — added in V18; set for STOCK_WRITTEN_OFF only |
| details | text — what changed, in words, as of that moment; never a password |
| created_at | timestamp |

Added in V12 (table `audit_log`) for Business Rule 17. **Append-only**: no row is ever updated or
deleted. Enforced at four levels — no endpoint but GET, `AuditLogRepository` extends Spring Data's bare
`Repository` (not `JpaRepository`, which would bring `deleteAll`), the entity is Hibernate `@Immutable`
with no setters, and V12's triggers make UPDATE, DELETE and TRUNCATE fail in the database itself. Every
foreign key is ON DELETE NO ACTION, so a user or facility that appears in the log can never be deleted.
**Exactly one target per row, matching its action** — V14's `audit_log_target_matches_action` CHECK
replaces the NOT NULL it removed from `target_user_id`, so an account action can never point at nobody;
V18 extended it so a STOCK_WRITTEN_OFF entry points at its batch and at nothing else.

### Relationships
User 1–1 Donor · Donor 1–N Donation · BloodBank 1–N BloodInventoryBatch · Donation 0..1–1 BloodInventoryBatch ·
BloodInventoryBatch 1–N ContributionAllocation · BloodRequestContribution 1–N ContributionAllocation ·
BloodInventoryBatch 1–N BatchWriteOff · User 1–N BatchWriteOff · BloodInventoryBatch 1–N AuditLog (as target) ·
BloodBank 1–N Donation ·
BloodBank 1–N BloodRequest (as fulfiller) · Hospital 1–N BloodRequest · User 1–N Notification ·
BloodRequest 1–N BloodRequestDecline · BloodBank 1–N BloodRequestDecline ·
BloodRequest 1–N BloodRequestContribution · BloodBank 1–N BloodRequestContribution ·
BloodBank 1–N AppealCooldown · User 1–N AuditLog (as actor) · User 1–N AuditLog (as target) ·
BloodBank 1–N AuditLog (as target) · Hospital 1–N AuditLog (as target) ·
BloodBank 1–N Notification (as appeal source)

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
   The units judged are **usable** units only (Business Rule 24): stock in expired batches is not
   counted, so it can never hold a group above a threshold it has really fallen below.
4. **`Donation.blood_type` is independently recorded, not copied from `Donor.blood_type`.** Blood type
   is re-verified by testing at every donation; the donation record must reflect what was tested at
   that time, not silently follow later edits to the donor's profile.
5. **BloodRequest status lifecycle** — **superseded by Business Rule 20.** It now reads
   PENDING → PARTIALLY_FULFILLED → FULFILLED (or straight to FULFILLED), or PENDING → REJECTED;
   APPROVED no longer exists. What survives unchanged is the half that always mattered: units
   leaving a bank must leave its stock in the same transaction as the record of them leaving. Since
   Business Rule 24 that means: deducted from that bank's usable batches first expiry first out
   (`BloodInventoryBatch.units_remaining`), with one `ContributionAllocation` row per batch drawn on,
   in the same transaction as the contribution.
6. **A hospital's request can be fulfilled by any blood bank**, not only one tied to that hospital —
   there is no fixed hospital-to-blood-bank ownership.
7. **Superseded by Business Rule 24 (V19): stock is no longer merged, and no batch inherits another's
   date.** The old rule folded every new batch into one row per (bank, group) and kept the *earlier*
   expiry date. Because that date could only ever move earlier, a fresh batch added to a row whose
   date had passed was stamped with the past date, and expired units were counted as stock everywhere
   (pending item #4). Now **each "Add New Batch" and each recorded donation creates a new batch with
   its own expiry date**, and nothing is ever merged. What the old rule protected still holds through
   the per-group figure: a group's `expiryDate` is the earliest expiry among its *usable* batches, so
   sooner-expiring stock is never hidden by a newer batch — and stops being shown once those units are
   gone. A donation's batch gets **donation date + 42 days** (`StockPolicy.DONATION_SHELF_LIFE_DAYS`)
   unless staff enter the date from the bag label. **Flagged for review:** confirm 42 days matches the
   preparation Makeni's labs actually use.
8. **A BLOOD_BANK_STAFF user is tied to exactly one blood bank** via a nullable `blood_bank_id` on
   `User` (null for all other roles). **Every action that adds or removes blood units is restricted to
   active staff of the bank whose shelf it changes, and ADMIN has no way to make any of them** (Stages 3b
   and 3b-2, the project owner's decision): an administrator has read-only oversight of stock — stock
   figures, batch lists, batch history, the audit log, reports — and changes none of it, on any bank's
   behalf. The four such actions, and where each refuses ADMIN with a 403 before anything is read or
   written:
   - **Add New Batch** (`PATCH /api/inventory/{bank}/{group}`) and **write-off**
     (`POST /api/inventory/{bank}/batches/{batch}/write-offs`): SecurityConfig (non-GET
     `/api/inventory/**` is BLOOD_BANK_STAFF only) and `InventoryService.requireStockChangeAccess`, which
     also requires the bank in the path to be the caller's own.
   - **Record Donation** (`POST /api/donations`, which creates a batch): SecurityConfig
     (`/api/donations/**` is BLOOD_BANK_STAFF only) and `DonationService`.
   - **CONTRIBUTE** (`PATCH /api/requests/{id}`, which takes units off batches): `RequestService.act`
     only. That address also carries REJECT, which an ADMIN keeps (declining on a named bank's behalf
     moves no blood), and a URL rule cannot see which action the body asks for — so the security rule
     still admits ADMIN to `/api/requests/**` and the service refuses the contribution.

   All four share one rule, `ActingBankResolver.resolveForStockChange`: BLOOD_BANK_STAFF only (checked
   before anything is read), ACTIVE, assigned to a bank, acting as that bank — it has no ADMIN branch, and
   no bank id from the request is consulted. `ActingBankResolver.resolve`, under which an ADMIN names a
   bank to act for, is now used only for actions that move no blood: REJECT, donation appeals and the
   donor roster. Staff of one bank can never change another bank's stock: a `bloodBankId` they send is
   ignored and they act as their own bank. Known limit, not addressed: an administrator creates staff
   accounts and is shown their one-time password (Business Rule 14), so could sign in *as* that staff
   member; anything done that way is recorded against the staff account. There is no self-registration path for HOSPITAL_STAFF,
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
      `InventoryService` when a new batch arrives (Add New Batch or Record Donation). That is the only
      path by which usable stock can rise, so it is the only place an upward crossing can happen. Every
      figure in this rule is **usable** units (Business Rule 24): expired batches count for nothing, so
      a bank holding 35 units of which 10 have expired is short (25) and may appeal. Usable stock also
      falls silently at midnight UTC when a batch expires; no low-stock alert or cooldown change reacts
      to that (Business Rule 26's daily notices are about batches, not thresholds), but the button reads
      the new figure on the next load.
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
      but it changes the checksum: existing demo databases must be dropped and recreated. V19 is the
      latest case: it retired `blood_inventory`, so `V902` was rewritten to seed batches (2–3 per group,
      the same usable totals — 405 / 458 — plus one expired 5-unit O+ batch at Makeni), and a fresh demo
      database was booted to prove it.
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
      docker-compose is **demo only** (its header says so): it always runs the `demo` profile, and a
      `BLOODLINK_JWT_SECRET` already set in the shell outranks the one in `.env`.
    - **A missing or short secret is refused under every profile, local ones included** — an empty
      value, or fewer than `JwtSecretPolicy.MIN_SECRET_BYTES` (32) UTF-8 bytes after trimming, the 256
      bits HS256 needs. Checked before the placeholder rule, with its own `InsecureJwtSecretException.Reason`
      (`MISSING`, `TOO_SHORT`, `PUBLIC_PLACEHOLDER`) and its own failure-analyzer report naming the length
      found — never the value. The JWT library already refused a short key, but only as a bare
      `WeakKeyException` stack trace; this makes it a plain explanation. The placeholder (41 bytes) and
      the test profile's secret pass, so local development is unchanged (`JwtSecretPolicyTest` checks
      both). The key is still the secret's raw UTF-8 bytes — a Base64 secret is not decoded — because
      changing the derivation would change every key and sign every user out.
    - **Setting a real secret** is documented in the README ("Setting a real JWT secret"): 48 random
      bytes in Base64, generated in PowerShell without being shown, set for the session
      (`$env:BLOODLINK_JWT_SECRET`) or permanently (`[Environment]::SetEnvironmentVariable(..., 'User')`).
      Never committed; changing it invalidates every issued token. Every variable the code reads is
      listed, with its default, in `backend/.env.example` (the backend, which does not itself read a
      `.env` file), `frontend/.env.example` (Vite) and the root `.env.example` (docker-compose: the
      secret only). The root `.gitignore` covers `.env`, `.env.*`, `*.env`, every
      `application-*.properties` except `application-demo.properties`, and key/keystore files.
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
    - **No administrator action changes stock** (Stages 3b and 3b-2, Business Rule 8). An administrator
      can no longer add a batch, write one off, record a donation or contribute to a request, so the log
      never holds a stock change made by an administrator; it records their account and facility actions,
      and the write-offs made by bank staff. (Record Donation and contributions are not audit-logged at
      all: their own rows — `donations`, `blood_request_contributions` and `contribution_allocations` —
      are their record.)
    - **Widened to stock write-offs (Stage 3 of the batch-stock redesign).** The log also records
      STOCK_WRITTEN_OFF — units of a batch discarded by blood bank staff of that bank (Business Rule 24;
      never by an ADMIN since Stage 3b, Business Rule 8) — so its actor is bank staff, not an
      administrator. Administrators still read these entries on the Audit Log. Each entry points at its batch through
      `target_batch_id` and at nothing else (V18's one-target rule), and its `details` read like "Wrote off
      3 units of O+ from a batch at X expiring 2026-10-01 (already expired). Reason: Expired. 7 of 10 units
      remain in the batch." plus the note when there is one. Written by `AuditLogService.recordStockWrittenOff`
      (`Propagation.MANDATORY`, stamped with the same clock instant as the write-off). The Admin Audit Log
      shows these entries: `AuditLogEntryResponse.targetType` is `BATCH`, `targetName` describes the batch
      ("O+ batch, expiry 2026-10-01 — X", since a batch has no name), and the page labels the action
      "Written off" (red) with the subject kind "Stock batch" (`AdminConsole.jsx`).
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
    - **A new blood bank needs no inventory seeding.** No stock batches are written:
      `InventoryService.listInventory` already reports all eight groups for every bank, filling a group
      with no batch as 0 units — Critical under Business Rule 3 — and the first "Add New Batch" or recorded
      donation creates its first real batch (Business Rule 24).
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
      **Only active staff of the contributing bank may CONTRIBUTE; an ADMIN is refused (403)** before
      the request is locked (Stage 3b-2, Business Rule 8), while an ADMIN may still REJECT on a named
      bank's behalf.
      Since Business Rule 24 the deduction is **first expiry, first out** from the bank's usable
      batches of that group (`BatchStockService.issue`, `FefoAllocator`), and one `ContributionAllocation`
      row per batch drawn on records where the units came from; the allocations add up to the
      contribution's units, enforced before anything is written. Expired batches are never issued.
    - **Units are capped at what is still outstanding, not refused.** Between the operator reading
      the screen and pressing the button another bank may have covered part of it; that is not their
      mistake to be punished for, so the server records what is genuinely wanted and reports it back.
      **More units than the bank actually holds is refused** (409) — that is a mistake about its own
      shelf, and silently issuing fewer than typed would leave the operator believing they had sent
      more. The 409 names the **usable** units, the units being contributed and the expired units that
      could not be counted ("Not enough usable O+ in stock — 30 units available, 31 being contributed
      (10 expired units cannot be issued).").
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
      **Since Business Rule 24 there is a second lock, always taken second:** the acting bank's usable
      batches of the group, by `BloodInventoryBatchRepository.lockUsableBatchesInIssueOrder`, in issue
      order. Hibernate sends exactly `… where blood_bank_id=? and blood_type=? and units_remaining>0 and
      expiry_date>=? order by expiry_date,id for no key update` (read back from the SQL log in
      `BatchStockCutoverIT`). PostgreSQL sorts before it locks, so every transaction locks a bank's
      batches in the same order; request first, batches second is the only lock order anywhere, so no
      two transactions can each hold what the other needs. `FOR NO KEY UPDATE` is what Hibernate's
      PESSIMISTIC_WRITE means on PostgreSQL (the request lock above is the same mode): it is exclusive
      against every other contributor, and differs from `FOR UPDATE` only in letting another
      transaction's foreign-key check through, which needs no waiting here. A waiter re-reads the row
      after the lock is released, so it sees the units genuinely left. `PartialFulfilmentRaceIT` proves
      this too: one bank with two requests and stock in two batches (exactly one of two 4-unit
      contributions succeeds), eight threads taking 1 unit each from 5 units over three batches (exactly
      5 succeed, the shelf ends at 0, no deadlock), and a contribution racing a held batch lock (it
      waits, then sees the 2 units left and is refused).
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
      (Business Rule 2) — the filter takes its cutoff *date* from `Donor.eligibilityCutoff(today)`
      (the same method `getEligibilityStatus()` uses, shared with Business Rule 23's report) to
      compare against in the database, needed so pagination can filter before cutting the page.
    - **`BLOOD_BANK_STAFF` only, via `ActingBankResolver`** — bank staff act as their own bank
      and an ADMIN must name one (`bloodBankId`); a donor, hospital staff or unauthenticated
      caller is refused (403/403/401), the same pattern every other bank-scoped endpoint uses.
    - **Search does not go through `PhoneNumber.normalize`.** That function validates a complete
      number and would reject a deliberate partial search ("900", "076900"); instead
      `PhoneNumber.searchFragment` strips spaces and hyphens, rewrites a leading "0" to "+232"
      (Business Rule 13's local-form mapping), and the rest is matched as a substring against the
      stored number. **`%` and `_` in the search are literal**, not LIKE wildcards: every search
      pattern is built by `LikePattern.contains` (backslash-escaped, queried with `escape '\'`).
      Shared with Business Rule 22.
22. **The Admin Console's User Accounts table is searched, filtered, sorted and paged by the server,
    one page at a time — and no screen downloads every account.**
    - **`GET /api/admin/users`** returns `{ content, page, size, totalElements, totalPages }` (the
      same envelope as the Audit Log and the Donors roster). Optional parameters, every one checked
      against a fixed list, a bad value being a 400 naming the field and never corrected:
      `page` (0-based, default 0); `size` (default 13, max 100); `q` (name or email,
      case-insensitive, or phone in any format via `PhoneNumber.searchFragment`; `%`/`_` literal
      via `LikePattern`); `role` (`DONOR`, `HOSPITAL_STAFF`, `BLOOD_BANK_STAFF`, `ADMIN`); `status`
      (`ACTIVE`, `SUSPENDED` — "Suspended" is the console's word for a disabled account); `sort`
      (`name`, `role`, `createdAt`, `status`) and `direction` (`asc`, `desc`).
    - **Search, both filters and paging run as one query** (`UserRepository.searchAccounts`), and its
      count query shares the same conditions, so `totalElements`/`totalPages` count only what
      matched. **The ORDER BY comes only from `AdminUserService.SORT_PROPERTIES`**, a whitelist of
      four entity properties, with `id` always added last as a tie-breaker so equal values never
      swap places between pages. A page past the end returns empty content, not an error.
    - **`GET /api/admin/reports/users`** returns `{ total, donors, hospitalStaff, bloodBankStaff,
      admins }`, counted by the database (zero-filled). It feeds the Dashboard's "Total Users" and
      "Registered Donors" cards and the counts on the role buttons — these used to be counted over
      a download of every account, and that full-list response no longer exists.
    - **Frontend:** a new page is fetched only when Previous/Next, a filter, the sort, the page size
      or the search changes — the search waits 350ms after typing stops, like the Donors page;
      nothing is prefetched. Changing any of those returns to page 0 and keeps the others;
      "Clear filters" (shown only when search or a filter is active) keeps the sort and page size.
      Page sizes offered are only 10 and 13, the ones that fit a 768px-tall screen without
      scrolling (checked in headless Chrome at 1366×768, 720 and 680). After Create / Edit /
      Suspend / Activate the current page is fetched again and pulled back if it is now past the
      end. All of this logic is in `frontend/src/lib/userAccounts.js` and `lib/pagination.js`.
    - **Unchanged:** ADMIN only (`/api/admin/**`); no password hash in any response
      (`AdminUserResponse` has no such field); create / edit / suspend / reactivate and their audit
      entries (Business Rules 14 and 17) behave exactly as before. No migration and no index were
      added: a "contains" search (`%text%`) cannot use an ordinary index anyway, and the table is
      small. Revisit (e.g. a trigram index) only if the account list grows into the tens of thousands.
23. **The Admin Console's Reports page is a read-only, citywide summary for ADMIN only — aggregates
    only, never a donor's personal details — and every figure on it is counted by the database.**
    Distinct from the Audit Log (Business Rule 17), which records what administrators did; Reports
    describes the state of the system. Four sections, each with its own "Download CSV".
    - **Endpoints** (`AdminReportController`, `ReportSummaryService`, package `com.bloodlink.admin.report`),
      all GET, all under SecurityConfig's `/api/admin/**` rule (any other role 403, no token 401):
      `GET /api/admin/reports/summary/stock`, `/summary/requests?from&to`, `/summary/donations?from&to`,
      `/summary/donors`, and each of the four with `/export` appended for its CSV. The three older
      Dashboard routes (`/api/admin/reports/donations`, `/requests`, `/users`) are unchanged.
    - **Counted by the database, never by loading rows** — every figure is a COUNT / SUM / GROUP BY
      query; Java only fills in the zero rows an aggregate leaves out (a group, status, urgency, month
      or bank with nothing in it) so every table always lists every row.
    - **Existing rules reused, never copied.** Stock status is `StockStatus.fromUnits` and its
      thresholds (`CRITICAL_BELOW` 15, `LOW_BELOW` 40), applied per bank and group to the usable units the
      database sums per (bank, group) (Business Rule 3). Usable, expired and expiring soon are
      `StockPolicy`'s (Business Rule 24), with `StockPolicy.EXPIRING_SOON_WINDOW_DAYS` (28) and the stock
      section's "today" from its UTC clock (`ReportSummaryService.stockDate`). Eligibility is `Donor.eligibilityCutoff(today)` —
      now the one place the 90-day cutoff date is computed, used by `Donor.getEligibilityStatus()`, the
      Donors roster (Business Rule 21) and this report alike (Business Rule 2); `ReportsIT` proves the
      database's eligible count equals `isEligibleToDonate()` applied donor by donor.
    - **Stock is USABLE stock only — reversed by Business Rule 24 (V19).** This section used to count
      units past their expiry date as stock, because the old Inventory screen did. Now an expired batch
      is not stock anywhere: its units are excluded from the total, from every group's and bank's figure
      and from "expiring soon" (usable units expiring within 28 days), and are reported only on their
      own, as **expired units awaiting discard** (`StockReport.pastExpiryUnits`, a name kept from before
      so the page needs no change; the CSV says "Expired units awaiting discard (not counted as
      stock)"). A bank's Critical count includes the groups it has no usable stock of at all (zero
      units), exactly as its own Inventory screen shows them. The status wording is the app's own —
      Critical / Low / Sufficient. **Known interim wording, until Stage 4:** the Reports page's note
      under the expiring card still reads "Includes N units already past expiry" (`AdminReports.jsx`),
      which is no longer true; the figures it labels are correct.
    - **Requests:** counted by `created_at` within the range. Fulfilment rate = FULFILLED ÷ (FULFILLED +
      REJECTED); PENDING and PARTIALLY_FULFILLED are still open and excluded. With nothing decided the
      rate is null and the page and CSV say **"No data"** — never 0% or NaN. "Filled by more than one
      bank" counts requests whose contribution rows (Business Rule 20's source of truth) name two or
      more distinct banks, whatever their status now.
    - **Donations:** counted by `donation_date` within the range — per calendar month (every month in
      the range, zero-filled), per blood bank (every bank) and per tested blood group (Business Rule 4).
    - **Donors:** total `Donor` rows, eligible now, in 90-day recovery (total − eligible), and per
      profile blood group plus "Unknown" for donors not yet typed. Note the total counts donor
      *profiles*, so it can differ from the number of DONOR *accounts* if an account has no profile.
    - **Date range:** `from` / `to`, ISO dates, both inclusive, in UTC (Sierra Leone's own time).
      Default: the 1st of the month five months back until today — the Dashboard chart's six months.
      **A 400 naming the field, never corrected:** a malformed date (that field), `from` after `to`
      (`from`), longer than five years (`to`). Stock and Donors are "as of today" and take no range.
    - **No personal donor data, anywhere.** The response records (`ReportDtos`) have no field that
      could hold a name, phone, email, address or date of birth — `ReportSummaryServiceTest` scans
      them — and `ReportsIT` checks a seeded donor's details appear in no section and no CSV.
    - **CSV, built on the server** from the same objects the page shows (`ReportCsv`): UTF-8 with a
      byte-order mark (so Excel reads it as UTF-8), CRLF rows, every text cell quoted with inner quotes
      doubled, numbers bare. **Formula injection defused:** a text cell starting with `=`, `+`, `-`,
      `@`, tab or carriage return is prefixed with an apostrophe so a spreadsheet shows it as text —
      facility names are typed by administrators. Named `bloodlink-report-<section>-<from>-to-<to>.csv`
      (or `-<date>.csv` for Stock and Donors), served as an attachment. SecurityConfig's CORS now
      exposes `Content-Disposition` so the page can read that file name from another origin.
    - **Exports are not audit-logged.** Recording "administrator exported report X" would need a new
      action in `audit_log_action_check` and a target-less case in `audit_log_target_matches_action`
      (both V12/V14 constraints) — a migration, so it was skipped as out of scope. Revisit with the
      owner if exports need to be traceable.
    - **No migration and no index added.** The aggregates scan small tables; the donations index V1
      already has (and V17 kept) is unchanged.
    - **Frontend** (`frontend/src/pages/AdminReports.jsx`, logic in `lib/reports.js`): opened from the
      Reports sidebar link (previously inert). Each section loads on its own with its own loading,
      empty ("No data for this period") and error-with-Try-again state. The range is applied with an
      Apply button and refetches only Requests and Donations; it is checked in the browser with the
      server's own three rules first, which saves a request but is not the control. Plain CSS bars and
      tables — no chart library or other dependency. The CSV is fetched with the token
      (`api/client.js` `apiDownload`) and saved as a file.
24. **Stock is held batch by batch, each batch with its own expiry date, and only USABLE stock is ever
    counted, shown as available, judged or issued.** Replaces Business Rule 7's one row per (bank,
    group) with a single merged date, which counted expired blood as stock everywhere, gave fresh stock
    stale dates, could not issue earliest-expiry-first, and had no way to record a discard. Schema:
    `V18` (tables), `V19` (cutover). The definitions, owned by `StockPolicy` (`com.bloodlink.stock`)
    and used by every screen, rule, alert and report — the frontend is sent results, never the rules:
    - **Today** is the UTC date from the injected `Clock` (`TimeConfig`, `Clock.systemUTC()`; Sierra Leone
      is UTC+0, so it is also the local date). Only stock code reads that clock; other `LocalDate.now()`
      calls are unchanged. Every stock query takes `:today` as a parameter — none reads the database's
      own `current_date` — so every figure agrees on the date, and tests can move it.
    - **Expired:** `expiry_date < today`. The expiry date is the **last usable day, inclusive**: a batch is
      usable through the whole of it and expired from 00:00 UTC the next day.
    - **Usable:** `expiry_date >= today` with units remaining. A group's usable units are the sum over
      its usable batches. `InventoryItemResponse.unitsAvailable` is usable units; `expiryDate` is the
      earliest expiry among usable batches (null if none).
    - **Expiring soon:** usable and `expiry_date <= today + 28` (`EXPIRING_SOON_WINDOW_DAYS`), today
      included, counted batch by batch — only the units that really expire soon, never a whole group
      because its earliest batch does. Already-expired units are never "expiring soon".
    - **Expired units are shown, never counted:** reported as `expiredUnits` (inventory rows and summary)
      and `pastExpiryUnits` (Admin Reports), "awaiting discard" — discarded with the write-off action below.
    - **Writing stock off (Stage 3).** `POST /api/inventory/{bank}/batches/{batch}/write-offs` with
      `{ units, reason, note? }` takes units off one batch and records why (`BatchWriteOffService`).
      **Who:** the bank's own active BLOOD_BANK_STAFF only, by the same `InventoryService` rule as Add New
      Batch (`requireStockChangeAccess`). **ADMIN is refused (403)**, expired batches included (Stage 3b,
      Business Rule 8), by SecurityConfig and again in that rule, before any bank is looked up or anything
      written; ADMIN keeps read access to the batch list and history. Hospitals and donors are refused by
      SecurityConfig (403), no token is a 401, staff naming another bank a 403, and a batch of another
      bank asked for under the caller's bank is a 404 like a missing one. **What:** `units` from 1 up to the
      batch's units remaining; `reason` one of EXPIRED, DAMAGED, FAILED_TESTING, DATA_ENTRY_ERROR, OTHER
      (matched exactly; anything else a 400 on `reason`); `note` optional, ≤ 500 characters, blank stored
      as none, **required for OTHER** (a 400 on `note`) — `BatchWriteOff.problemWithNote`, one copy shared
      with the entity. More units than remain, or a batch with none left, is a **409** naming the units
      left, never silently reduced (the same reasoning as Business Rule 20's "more than the bank holds").
      **Expired batches may be written off** — the main use. **How:** the batch row is locked with
      `BloodInventoryBatchRepository.lockById` (PESSIMISTIC_WRITE, i.e. `FOR NO KEY UPDATE`, the mode a
      contribution's batch lock uses; read back in `BatchWriteOffIT`), and the remaining units are judged
      only after the lock is granted. A write-off takes that one lock and no other, so it can never deadlock
      with a contribution (request first, batches second); `PartialFulfilmentRaceIT` races the two for one
      batch and exactly one wins, never more units out than were there. The deduction, the
      `batch_write_offs` row and the STOCK_WRITTEN_OFF audit entry (Business Rule 17) are one transaction,
      all stamped with one instant from the injected `Clock` (the batch's `last_updated` too, via
      `BloodInventoryBatch.deductUnits(units, at)`). If the batch was usable, usable stock falls and
      `notifyStockCrossing` runs exactly as after a contribution, so a write-off that takes a group into Low
      or Critical raises a LOW_STOCK_ALERT; writing off expired units changes no stock figure and alerts
      nobody. **Answers 201** with `{ writeOff, batch }` — the entry in the batch history's `writeOffs`
      shape, and the batch as it now stands. Nothing is undone or bulk-discarded: one batch per call.
    - **New stock is always a new batch.** Add New Batch (`MANUAL`) and Record Donation (`DONATION`,
      linked by unique `donation_id`, carrying the donation's units) each create one; nothing is merged.
      **An entered expiry must be today or later and at most 365 days away**
      (`StockPolicy.MAX_ENTERED_EXPIRY_DAYS`): anything else is a 400 on `expiryDate` with nothing
      written — Add New Batch's required date and Record Donation's **optional** `expiryDate` alike. With
      no date, a donation's batch expires donation date + `DONATION_SHELF_LIFE_DAYS` (42, provisional).
    - **First expiry, first out.** A contribution takes the earliest-expiring usable batch first, then
      the next (ties by id in PostgreSQL's unsigned uuid order — `FefoAllocator.compareAsPostgres`, so
      the order Java allocates in is the order the database locks in); see Business Rule 20 for the
      locks and allocations. Expired or empty batches are never allocated, even if passed in.
    - **Provenance of migrated batches.** V19 turned every old row with units > 0 into one `MIGRATED`
      batch reusing the row's id, with received = remaining = the old count and received_at /
      last_updated = the old last_updated. Its expiry is the old merged "earliest of" date, so it is
      conservative (may say good units expire sooner, never later) and should be checked against the
      bag labels; the real per-batch split is not recoverable. Old rows with 0 units became no batch,
      listed in the migration output (`DB: V19: skipped …`). Batches already expired at the cutover are
      stamped `expiry_noticed_at`. V19 refuses to run (one transaction, nothing changed) on negative units
      or a non-empty batch table, verifies per-bank, per-group totals before finishing, and renames the
      old table to `blood_inventory_pre_batches` (dropped by V24).
    - **Batch detail is for the bank's own staff and ADMIN only.** `GET /api/inventory/{bank}/batches`
      (live batches — any units left, expired included — optional `?bloodType=`) and
      `GET /api/inventory/{bank}/batches/{id}` (one batch: state, days to expiry, source, donation, and
      where its units went — issues to requests by hospital, and write-offs). SecurityConfig refuses
      hospitals and donors (403) before the service; staff of another bank are refused in
      `InventoryService` (403); a batch asked for under the wrong bank is a 404 like a missing one.
      **Hospitals never see batch-level detail**: their availability view reads the per-group totals
      only, whose first six JSON fields are unchanged (`InventoryApiContractIT` pins them).
    - **Known limits:** usable stock falls at midnight UTC with no event, so no low-stock alert fires for
      an expiry (staff hear about expiring and expired batches from Business Rule 26's daily notices, which
      judge no threshold; a written-off figure in Admin Reports is future work, deliberately not built); stock edited directly in the database raises no
      alert (as before). **Interim screens until Stage 4:** the Inventory table's own "Expiring Soon"
      column still works out its figure in the browser from the group's `expiryDate` and shows the
      group's whole usable total (the summary card above it is right), and the Admin Reports note
      described in Business Rule 23.
25. **A donor can accept a donation appeal, and the bank that sent it is told.** "Respond" on the Donor
    Portal's appeals panel used to do nothing. Schema: `V20`–`V22` (columns on `notifications`, see its
    schema section); built by the other project member and merged on 2026-10-24.
    - **`POST /api/notifications/{id}/accept`**, DONOR only (SecurityConfig, matched before the general
      notifications rule; any other role 403). `NotificationService.acceptAppeal`: the notification must
      be a DONATION_APPEAL addressed to the caller — another user's id, or any other type, is a 404 like a
      missing one. Answers with the notification, now `accepted` and `read`.
    - **An ineligible donor cannot accept (409, nothing written)** — read from `Donor.isEligibleToDonate()`,
      the same Business Rule 2 calculation every other eligibility gate uses. The Donor Portal hides Respond
      for an ineligible donor, and on a 409 re-reads the profile so the button disappears.
    - **Accepting is a statement of intent, nothing more.** It books no slot, reserves no stock and changes
      no eligibility; the donation is still recorded by bank staff when the donor arrives (Record Donation).
      Acceptance is not withdrawable.
    - **Every BLOOD_BANK_STAFF account assigned to the sending bank gets a DONATION_APPEAL_ACCEPTED
      notification** (the same recipient query as a low-stock alert) ("<donor name> accepted your appeal for O+."), routed by the appeal row's
      `source_blood_bank_id` — never by parsing the message. Written in the same transaction as the
      acceptance, like every other notification, then pushed.
    - **Idempotent, and exactly one staff alert per acceptance.** The row is locked
      (`NotificationRepository.findByIdAndRecipientIdForUpdate`, PESSIMISTIC_WRITE) and
      `acceptance_alert_sent` records that the alert went out, so a double click or a retry sends nothing
      twice; a retry also repairs an acceptance recorded before alerts existed (what V22 did in bulk).
      An appeal with no `source_blood_bank_id` (one V21 could not attribute) is still accepted, but alerts
      nobody.
    - **The Inventory row shows "Donor Responded".** `InventoryItemResponse.appealAccepted` (appended after
      `batchCount`, so the contract fields Business Rule 24 pins are unchanged) is true when the group has a
      running cooldown (Business Rule 12) and a donor accepted an appeal sent at or after that cooldown's
      `last_sent_at` (`NotificationService.acceptedAppealTimes`). The button stays disabled exactly as
      before — acceptance changes no appeal rule. Wording lives in `frontend/src/lib/appealStatus.js`. The
      Inventory screen refreshes on a DONATION_APPEAL_ACCEPTED push.
    - **Every portal re-reads its notifications every 30 seconds** (`useNotifications`), so a staff member
      whose socket dropped still sees an acceptance — the socket stays an accelerator, the row the record.
    - **Known limits:** the demo seed's three appeals (`V905`) are inserted after V21's back-fill, so they
      carry no source bank and accepting one in the demo alerts no staff; an appeal can be accepted however
      old it is.
    - **Migration renumbering:** this feature was written as V18–V20 on code older than the batch-stock
      work, colliding with V18/V19 (batch stock, already applied to `bloodlink_dev`) and the V20 reserved for
      dropping `blood_inventory_pre_batches`. It was renumbered V20 / V21 / V22 with contents unchanged, and
      the drop became V23, now **V24** (Stage 6) since Business Rule 26 took V23. None of the three touches stock tables. The applied V18/V19 files,
      the table comment V19 stores on `blood_inventory_pre_batches` and `V19CutoverMigrationIT` still say
      "V20": left as they are, since editing an applied migration changes its checksum and Flyway would then
      refuse to start on `bloodlink_dev`. V24 replaces the stale comment simply by dropping the table.
26. **Every day, a blood bank's active staff are told which of its batches expire in 7 days, and which
    have expired and still hold units.** The project owner's decisions, final. Schema: `V23` (the two
    notification types only; no table or column).
    - **Seven-day reminder (BATCH_EXPIRY_REMINDER), informational only:** a usable batch with units left
      that is exactly 7 days from its expiry date (`BatchStockService.EXPIRY_REMINDER_DAYS`) is listed
      once — "2 batches at X will expire on 31 Oct 2026: O+ (5 units), A- (1 unit)." It never tells staff
      to remove anything. **Separate from Business Rule 24's 28-day "expiring soon"**, which is unchanged.
    - **Expired notice (BATCH_EXPIRED):** from the day **after** the expiry date (expired under Business
      Rule 24: `expiry_date < today` UTC), a batch still holding units is listed every day until it has
      none left — "2 batches at X have expired and still hold units. Please write them off: O+ (5 units,
      expired 21 Oct 2026), …". Nothing is sent on the expiry date itself, a written-off (or issued-out)
      batch drops out, and a batch with 0 units is never listed by either notice. Batches V19 stamped as
      already expired at the cutover are included: they still need writing off.
    - **At most one notice of each type per bank per UTC day, listing all its batches**, sent to that
      bank's **ACTIVE** BLOOD_BANK_STAFF only (`UserRepository.findByRoleAndBloodBankIdAndStatus`) — no
      suspended staff, administrators, hospital staff or donors. (Every other staff alert still goes to
      every staff account of the bank, suspended included, as before.) Each row is a normal Notification —
      written first, pushed after commit, re-read by the 30-second poll — with `source_blood_bank_id` set to
      the bank and `created_at` from the injected Clock.
    - **The job** (`BatchExpiryNotificationJob`, enabled by `@EnableScheduling` in `SchedulingConfig` — the
      only scheduled task) runs at `bloodlink.expiry-notifications.cron`, default `0 0 6 * * *` read in UTC,
      and **also once at startup if today's time has already passed**, so a server that was off at 06:00
      (a laptop) still sends that day's notices. `bloodlink.expiry-notifications.enabled=false` removes it;
      `application-test.properties` does, since a startup run would write outside any test transaction.
      The date judged comes from the injected `Clock`, never the system clock. Each bank is its own
      transaction (`NotificationService.notifyBatchExpiry`), so one bank failing is logged and the others
      still run.
    - **No duplicates, no migration for the record.** In one transaction per bank: lock the bank row
      (`BloodBankRepository.lockById`), then send a type only if no notice of that type exists for the bank
      with `created_at` inside today (`NotificationRepository.existsForBankBetween`). So a second run the
      same day — a restart, or two servers — sends nothing. The reminder's once-per-batch record is the
      existing `expiry_noticed_at` column (V18, unused until now), stamped by a one-column bulk update so it
      can never overwrite a concurrent stock change nor touch `last_updated`.
    - **Catch-up after downtime:** a reminder due on a day the job did not run is sent by the next run, as
      long as the batch was already in stock on its seventh day before expiry (received on or before
      expiry − 7, UTC) and is still usable with units. A batch entered with fewer than 7 days left never gets
      one (staff entered that date themselves). Batches caught up with different dates read "will expire
      within 7 days" and each carries its own date. A bank with no active staff gets nothing and nothing is
      stamped, so the reminder still reaches staff assigned later. A missed day's expired notice is not
      repeated — the next day's lists the same batches.
    - **Frontend:** no change was needed for the notices to appear — the bell lists every type, with a grey
      dot for types it has no colour for. Not built (reported, pending a decision): a dot colour for the two
      types, and refreshing the Inventory screen when one arrives.

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
- **Chapter 3/4 — notification types.** A fifth type, REQUEST_PROGRESS, exists (V15), a sixth,
  DONATION_APPEAL_ACCEPTED (Business Rule 25, V21), and a seventh and eighth, BATCH_EXPIRY_REMINDER and
  BATCH_EXPIRED (Business Rule 26, V23), sent by a daily scheduled job — the first scheduled task in the system.
- **Chapters 3, 4 and 5 — ERD, use cases and narrative for appeal acceptance (Business Rule 25, V20–V22).**
  `Notification` gains `accepted`, `source_blood_bank_id` (BloodBank 1–N Notification), `blood_type` and
  `acceptance_alert_sent`; the donor's "respond to donation appeals" use case now has a real flow.
- **Chapters 3, 4 and 5 — ERD and narrative for facilities.** Blood banks and hospitals are no
  longer purely seeded: an administrator creates them (Business Rule 19, V14), names are unique per
  type, and `audit_log` gained `target_blood_bank_id` / `target_hospital_id` with two new actions.
- **Chapters 3, 4 and 5 — ERD and narrative for stock (Business Rule 24, V18/V19).** `BloodInventory`
  is replaced by `BloodInventoryBatch` (one row per batch, own expiry date; usable stock derived, not
  stored), with `ContributionAllocation` (Contribution 1–N, Batch 1–N), `BatchWriteOff` (Batch 1–N,
  User 1–N), Donation 0..1–1 Batch, and `audit_log.target_batch_id` / STOCK_WRITTEN_OFF. Narrative: why
  per-batch stock (expired units were counted as stock), the expired / usable / expiring-soon
  definitions, first-expiry-first-out issuing (a sequence figure), and the "discard stock" use case
  (Stage 3). Business Rule 7's merging is gone.

## Actors (roles) and what each can do

- **Donor** — view own profile/history/eligibility, accept donation appeals while eligible (Business Rule 25)
- **Hospital Staff** — submit blood requests, track their hospital's requests including how many units have arrived and from which banks (Business Rule 20), view live inventory across blood banks (read-only)
- **Blood Bank/Lab Staff** — manage own bank's inventory, batch by batch (each batch with its own expiry date; view their own bank's batches and each batch's history, and write units off a batch with a reason — Business Rule 24; every stock change — adding, writing off, recording donations, contributing — is theirs alone, Business Rule 8), contribute units to incoming requests or decline them (Business Rule 20), send donation appeals and be told when a donor accepts one (Business Rule 25), be told daily of their bank's batches 7 days from expiry and of expired batches still to write off (Business Rule 26), view the roster of donors who have given at their own bank and each one's donation history there (Business Rule 21)
- **Administrator** — create staff and administrator accounts (Business Rule 14), add new blood banks and
  hospitals (Business Rule 19), manage all user accounts (search, filter, sort and page through them — Business Rule 22 — edit another account's phone and facility, activate/suspend), view every bank's stock and batches read-only — an administrator cannot add a batch, write one off, record a donation or contribute units to a request, though they may still decline a request on a named bank's behalf (Business Rules 8, 20, 24), view the audit log of administrator actions and stock write-offs (Business Rule 17), view aggregate reports (donation trends and request outcomes on the Dashboard; the Reports page's stock, requests, donations and donors summaries with CSV export — Business Rule 23)
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
    `DonorOptionalEmailIT`, `AdminFacilityCreationIT`, `PartialFulfilmentIT`, `DonorRosterIT`,
    `AdminUserSearchIT`, `ReportsIT`, `BatchStockSchemaIT`, `BatchStockCutoverIT`, `InventoryApiContractIT`,
    `BatchWriteOffIT`, `AdminStockLockoutIT`, `BatchExpiryNotificationIT`),
    every one of which is `@Transactional` and rolls back everything it writes,
    so re-running them never leaves residue to clean up.
    Create it once with `createdb bloodlink_test`; Flyway builds the schema on first use like anywhere else.
    Never point it at `bloodlink_dev`, and **never add a consumer that is not `@Transactional`** — that,
    not the number of test classes, is what would break the "always safe to rerun" guarantee this
    convention exists for. A test that genuinely cannot roll back belongs on a database of its own:
    `PartialFulfilmentRaceIT` (Business Rule 20) needs two real committed transactions to race, so it
    creates `bloodlink_race_test` before its Spring context starts and drops it afterwards, touching
    nothing shared. That is the pattern for any future test in the same position — and the one
    `V19CutoverMigrationIT` follows: it must stop the schema at V17 and run real, committed Flyway
    migrations, so it creates `bloodlink_migration_test` before each test (plain JUnit, no Spring
    context) and drops it afterwards.
    **Enforced, not just stated:** an environment variable outranks `application-test.properties`, so a
    `SPRING_DATASOURCE_URL` left set in a terminal would silently redirect the tests. `IntegrationDatabaseGuard`
    (test classpath only, registered in `src/test/resources/META-INF/spring.factories`) refuses any start
    under the `test` profile whose database is not `bloodlink_test` or `bloodlink_race_test`, before a
    connection is opened. (Originally worded as "exactly one test, `BloodRequestLifecycleIT`";
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
