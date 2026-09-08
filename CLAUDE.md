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

REST is synchronous (requests, CRUD). WebSocket is one-directional server→client push, used only for:
low-stock alerts to blood bank staff, and donation appeals to donors.

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
| phone | varchar |
| blood_bank_id | UUID, FK -> BloodBank, nullable — set only for BLOOD_BANK_STAFF (V5) |
| hospital_id | UUID, FK -> Hospital, nullable — set only for HOSPITAL_STAFF (V7) |
| created_at | timestamp |

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

Seeded, not self-registering: **Makeni Regional Hospital**, **Holy Spirit Hospital** (V6, fixed ids
`3333…` and `4444…`, continuing V4's `1111…`/`2222…` for the blood banks).

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
| patient_reference | varchar, nullable — optional ward / patient label from the request form |
| fulfilled_by_blood_bank_id | UUID, FK -> BloodBank, nullable until approved |
| created_at | timestamp |

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
BloodBank 1–N BloodRequest (as fulfiller) · Hospital 1–N BloodRequest · User 1–N Notification

---

## Business Rules (decided — do not re-derive differently)

1. **Donation appeals flow from Blood Bank → Donor, never Hospital → Donor.** Hospitals request from
   blood banks; blood banks appeal to donors when their own stock is low. A donor's screen must never
   show a hospital as the source of an appeal. (This was a deliberate architecture correction — see
   Chapter 3, Section 3.5.1 rationale, if narrative context is ever needed.)
2. **Donor eligibility** = `last_donation_date` is more than 90 days ago (or null = always eligible).
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
8. **A BLOOD_BANK_STAFF user is tied to exactly one blood bank** via a nullable `blood_bank_id` on
   `User` (null for all other roles). Inventory writes are restricted to that staff member's own bank;
   ADMIN can write to any bank. There is no self-registration path for HOSPITAL_STAFF,
   BLOOD_BANK_STAFF, or ADMIN — only DONOR accounts are created via public registration.
9. **A HOSPITAL_STAFF user is tied to exactly one hospital** via a nullable `hospital_id` on `User`
   (V7) — the exact counterpart of rule 8. A new request is stamped with the caller's own hospital;
   `POST /api/requests` accepts no hospital id at all. A hospital sees only its own requests.
10. **A blood bank's request feed is "unclaimed, or already ours".** Because any bank may fulfil any
    request (rule 6), every bank sees every request still sitting at PENDING; approving sets
    `fulfilled_by_blood_bank_id` and moves the request into that bank's feed alone. Only the bank
    that approved a request may fulfil it.
11. **Stock is checked at approval and re-checked at fulfilment; only fulfilment deducts.** Approving
    without the units would strand the hospital behind a bank that took the request out of everyone
    else's feed, so it is refused. The authoritative check is at fulfilment, against stock at that
    moment: if the units have gone, the whole transition is refused (409) and nothing is deducted.
    Both the request row and the inventory row are read under a pessimistic write lock, so two banks
    acting at once cannot both pass the same check.
12. **Rejection is terminal for every bank, not just the rejecting one.** `status` is a single field
    with one REJECTED state (rule 5), so one bank's rejection closes the request; recording per-bank
    refusals would need a table the schema does not have. The hospital raises a fresh request instead.

## Actors (roles) and what each can do

- **Donor** — view own profile/history/eligibility, respond to donation appeals
- **Hospital Staff** — submit blood requests, track their hospital's request status, view live inventory across blood banks (read-only)
- **Blood Bank/Lab Staff** — manage own bank's inventory, approve/reject/fulfil incoming requests, send donation appeals
- **Administrator** — manage all user accounts, view aggregate reports (donation trends, request outcomes)

## Conventions

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
