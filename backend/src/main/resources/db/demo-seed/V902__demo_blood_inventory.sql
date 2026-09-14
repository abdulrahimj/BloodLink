-- Blood inventory for both seeded banks, one row per (bank, blood group) as
-- the schema requires. Expressed as a current snapshot rather than replayed
-- from history: a real bank's stock table always reflects everything ever
-- added or issued, cumulatively, without needing to re-derive that history —
-- this seed states the resulting numbers directly, the same way an admin
-- doing a real stocktake would enter today's counts rather than every
-- transaction that produced them.
--
-- Expiry dates are relative to current_date, not fixed calendar dates, so the
-- Inventory screen's "Expiring Soon" figures still look meaningful whenever
-- this demo is actually started — a fixed date would look increasingly wrong
-- the further past it the demo is ever run.
--
-- Makeni Regional Blood Bank: 405 total units (matching BloodBankDashboard's
-- mockup figure exactly), with one Low group (A-) and two Critical (B-, AB-)
-- — CLAUDE.md Business Rule 3: below 15 = Critical, below 40 = Low.
insert into blood_inventory (blood_bank_id, blood_type, units_available, expiry_date) values
    ('11111111-1111-1111-1111-111111111111', 'O+',  130, current_date + 20),
    ('11111111-1111-1111-1111-111111111111', 'A+',   85, current_date + 45),
    ('11111111-1111-1111-1111-111111111111', 'B+',   60, current_date + 60),
    ('11111111-1111-1111-1111-111111111111', 'AB+',  45, current_date + 50),
    ('11111111-1111-1111-1111-111111111111', 'O-',   40, current_date + 35),
    ('11111111-1111-1111-1111-111111111111', 'A-',   25, current_date + 25), -- Low
    ('11111111-1111-1111-1111-111111111111', 'B-',   10, current_date + 15), -- Critical
    ('11111111-1111-1111-1111-111111111111', 'AB-',  10, current_date + 10); -- Critical

-- Holy Spirit Blood Bank: a healthier picture for contrast — one Low group
-- (AB-), nothing Critical — so the two banks do not tell an identical story.
insert into blood_inventory (blood_bank_id, blood_type, units_available, expiry_date) values
    ('22222222-2222-2222-2222-222222222222', 'O+',  110, current_date + 50),
    ('22222222-2222-2222-2222-222222222222', 'A+',   75, current_date + 55),
    ('22222222-2222-2222-2222-222222222222', 'B+',   58, current_date + 65),
    ('22222222-2222-2222-2222-222222222222', 'AB+',  48, current_date + 70),
    ('22222222-2222-2222-2222-222222222222', 'O-',   50, current_date + 45),
    ('22222222-2222-2222-2222-222222222222', 'A-',   42, current_date + 40),
    ('22222222-2222-2222-2222-222222222222', 'B-',   40, current_date + 38),
    ('22222222-2222-2222-2222-222222222222', 'AB-',  35, current_date + 26); -- Low
