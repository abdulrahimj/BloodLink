-- Demo stock for both seeded banks, as BATCHES (blood_inventory_batches, V18/V19):
-- 2 or 3 batches per blood group, each with its own expiry date, the way real
-- stock arrives (Business Rule 24). Expressed as a current snapshot rather than
-- replayed from history: this seed states what is on each shelf today, the same way
-- an admin doing a real stocktake would enter today's bags rather than every
-- transaction that produced them.
--
-- Runs after V19 (the demo-seed migrations are numbered from V900), so the old
-- one-row-per-group blood_inventory table no longer exists here; this file was
-- rewritten for batch stock, which changes its checksum. A demo database seeded by
-- the old V902 must be dropped and recreated (docker compose down -v, or drop
-- bloodlink_demo).
--
-- Expiry and arrival dates are relative to current_date / now(), not fixed calendar
-- dates, so "expiring soon" figures still look meaningful whenever the demo is
-- started. Every date is within the 365 days staff may enter. source = MANUAL:
-- these are stocktake entries, not recorded donations.
--
-- USABLE totals are exactly what the one-row seed showed, group by group, so every
-- screen tells the same story as before: Makeni Regional Blood Bank 405 usable units
-- (matching BloodBankDashboard's mockup figure), with one Low group (A-) and two
-- Critical (B-, AB-) — Business Rule 3: below 15 = Critical, below 40 = Low; Holy
-- Spirit Blood Bank 458, one Low group (AB-), nothing Critical. The earliest batch of
-- each group expires on the date the old row carried.
--
-- On top of that, ONE EXPIRED BATCH: 5 units of O+ at Makeni Regional that expired 3
-- days ago. It is shown as expired, awaiting discard, and counted in no total, so the
-- demo has something to show for expired stock. It is stamped expiry_noticed_at, as
-- V19 stamps stock that had already expired when batches began.

-- Makeni Regional Blood Bank
insert into blood_inventory_batches
    (blood_bank_id, blood_type, units_received, units_remaining, expiry_date, received_at, last_updated, source, expiry_noticed_at)
values
    ('11111111-1111-1111-1111-111111111111', 'O+',  70, 70, current_date + 20, now() - interval '22 days', now() - interval '22 days', 'MANUAL', null),
    ('11111111-1111-1111-1111-111111111111', 'O+',  60, 60, current_date + 48, now() - interval  '6 days', now() - interval  '6 days', 'MANUAL', null),
    ('11111111-1111-1111-1111-111111111111', 'O+',   5,  5, current_date -  3, now() - interval '45 days', now() - interval '45 days', 'MANUAL', now()), -- EXPIRED
    ('11111111-1111-1111-1111-111111111111', 'A+',  45, 45, current_date + 45, now() - interval '12 days', now() - interval '12 days', 'MANUAL', null),
    ('11111111-1111-1111-1111-111111111111', 'A+',  40, 40, current_date + 70, now() - interval  '3 days', now() - interval  '3 days', 'MANUAL', null),
    ('11111111-1111-1111-1111-111111111111', 'B+',  25, 25, current_date + 60, now() - interval  '9 days', now() - interval  '9 days', 'MANUAL', null),
    ('11111111-1111-1111-1111-111111111111', 'B+',  35, 35, current_date + 85, now() - interval  '2 days', now() - interval  '2 days', 'MANUAL', null),
    ('11111111-1111-1111-1111-111111111111', 'AB+', 20, 20, current_date + 50, now() - interval '14 days', now() - interval '14 days', 'MANUAL', null),
    ('11111111-1111-1111-1111-111111111111', 'AB+', 25, 25, current_date + 75, now() - interval  '4 days', now() - interval  '4 days', 'MANUAL', null),
    ('11111111-1111-1111-1111-111111111111', 'O-',  15, 15, current_date + 35, now() - interval '10 days', now() - interval '10 days', 'MANUAL', null),
    ('11111111-1111-1111-1111-111111111111', 'O-',  25, 25, current_date + 56, now() - interval  '5 days', now() - interval  '5 days', 'MANUAL', null),
    ('11111111-1111-1111-1111-111111111111', 'A-',  10, 10, current_date + 25, now() - interval '17 days', now() - interval '17 days', 'MANUAL', null), -- Low (25)
    ('11111111-1111-1111-1111-111111111111', 'A-',  15, 15, current_date + 52, now() - interval  '7 days', now() - interval  '7 days', 'MANUAL', null),
    ('11111111-1111-1111-1111-111111111111', 'B-',   4,  4, current_date + 15, now() - interval '27 days', now() - interval '27 days', 'MANUAL', null), -- Critical (10)
    ('11111111-1111-1111-1111-111111111111', 'B-',   6,  6, current_date + 40, now() - interval  '8 days', now() - interval  '8 days', 'MANUAL', null),
    ('11111111-1111-1111-1111-111111111111', 'AB-',  3,  3, current_date + 10, now() - interval '32 days', now() - interval '32 days', 'MANUAL', null), -- Critical (10)
    ('11111111-1111-1111-1111-111111111111', 'AB-',  7,  7, current_date + 33, now() - interval '11 days', now() - interval '11 days', 'MANUAL', null);

-- Holy Spirit Blood Bank: a healthier picture for contrast — one Low group
-- (AB-), nothing Critical — so the two banks do not tell an identical story.
insert into blood_inventory_batches
    (blood_bank_id, blood_type, units_received, units_remaining, expiry_date, received_at, last_updated, source, expiry_noticed_at)
values
    ('22222222-2222-2222-2222-222222222222', 'O+',  50, 50, current_date + 50, now() - interval '10 days', now() - interval '10 days', 'MANUAL', null),
    ('22222222-2222-2222-2222-222222222222', 'O+',  60, 60, current_date + 72, now() - interval  '2 days', now() - interval  '2 days', 'MANUAL', null),
    ('22222222-2222-2222-2222-222222222222', 'A+',  30, 30, current_date + 55, now() - interval  '9 days', now() - interval  '9 days', 'MANUAL', null),
    ('22222222-2222-2222-2222-222222222222', 'A+',  45, 45, current_date + 80, now() - interval  '1 day',  now() - interval  '1 day',  'MANUAL', null),
    ('22222222-2222-2222-2222-222222222222', 'B+',  18, 18, current_date + 65, now() - interval '13 days', now() - interval '13 days', 'MANUAL', null),
    ('22222222-2222-2222-2222-222222222222', 'B+',  40, 40, current_date + 90, now() - interval  '3 days', now() - interval  '3 days', 'MANUAL', null),
    ('22222222-2222-2222-2222-222222222222', 'AB+', 20, 20, current_date + 70, now() - interval '15 days', now() - interval '15 days', 'MANUAL', null),
    ('22222222-2222-2222-2222-222222222222', 'AB+', 28, 28, current_date + 95, now() - interval  '4 days', now() - interval  '4 days', 'MANUAL', null),
    ('22222222-2222-2222-2222-222222222222', 'O-',  25, 25, current_date + 45, now() - interval  '8 days', now() - interval  '8 days', 'MANUAL', null),
    ('22222222-2222-2222-2222-222222222222', 'O-',  25, 25, current_date + 68, now() - interval  '2 days', now() - interval  '2 days', 'MANUAL', null),
    ('22222222-2222-2222-2222-222222222222', 'A-',  12, 12, current_date + 40, now() - interval '18 days', now() - interval '18 days', 'MANUAL', null),
    ('22222222-2222-2222-2222-222222222222', 'A-',  30, 30, current_date + 66, now() - interval  '5 days', now() - interval  '5 days', 'MANUAL', null),
    ('22222222-2222-2222-2222-222222222222', 'B-',  15, 15, current_date + 38, now() - interval '20 days', now() - interval '20 days', 'MANUAL', null),
    ('22222222-2222-2222-2222-222222222222', 'B-',  25, 25, current_date + 62, now() - interval  '6 days', now() - interval  '6 days', 'MANUAL', null),
    ('22222222-2222-2222-2222-222222222222', 'AB-', 15, 15, current_date + 26, now() - interval '19 days', now() - interval '19 days', 'MANUAL', null), -- Low (35)
    ('22222222-2222-2222-2222-222222222222', 'AB-', 20, 20, current_date + 49, now() - interval  '7 days', now() - interval  '7 days', 'MANUAL', null);
