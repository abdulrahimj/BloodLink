-- Demo-only seed data. Runs ONLY when spring.flyway.locations includes this
-- folder — which application-demo.properties is the only config that does.
-- The default (dev/prod) locations list is just classpath:db/migration, so
-- bloodlink_dev's Flyway history never even sees this file, let alone runs it.
--
-- Versioned from V900 rather than continuing on from V8, so that ordinary
-- schema work in db/migration can keep numbering sequentially (V9, V10, ...)
-- for years without ever colliding with a demo-only version number. Flyway
-- merges all configured locations into one ordered history by version, so
-- the numbers only need to stay clear of each other, not be adjacent.
--
-- This file replaces the ad-hoc accounts previously created by hand in
-- pgAdmin for manual testing, with one formal account per role. All five
-- share one password for convenience: BloodLink@2026. The hash below is a
-- real BCrypt hash of that password, generated with this project's own
-- BCryptPasswordEncoder and verified against it before use — never typed
-- from memory, since a hand-typed hash would just fail every login.
--
-- Fixed ids (not gen_random_uuid()), matching the pattern blood_banks (V4)
-- and hospitals (V6) already set: later demo-seed files reference these
-- accounts by literal id (e.g. addressing a notification to bank staff).

insert into users (id, full_name, email, password_hash, role, phone, blood_bank_id, hospital_id, status) values
    ('a0000000-0000-0000-0000-000000000001', 'Fatmata Bangura',  'admin@blooddemo.example.com',
     '$2a$10$m30ekhcbhvl1mwZKF02fG.YIfa7THcm.bCWYGDiag4kRX6vuWSo/K', 'ADMIN',
     '+232 76 900 001', null, null, 'ACTIVE'),

    ('a0000000-0000-0000-0000-000000000002', 'Dr. Aminata Sesay', 'hospital.makeni@blooddemo.example.com',
     '$2a$10$m30ekhcbhvl1mwZKF02fG.YIfa7THcm.bCWYGDiag4kRX6vuWSo/K', 'HOSPITAL_STAFF',
     '+232 76 900 002', null, '33333333-3333-3333-3333-333333333333', 'ACTIVE'),

    ('a0000000-0000-0000-0000-000000000003', 'Sorie Kondeh', 'hospital.holyspirit@blooddemo.example.com',
     '$2a$10$m30ekhcbhvl1mwZKF02fG.YIfa7THcm.bCWYGDiag4kRX6vuWSo/K', 'HOSPITAL_STAFF',
     '+232 76 900 003', null, '44444444-4444-4444-4444-444444444444', 'ACTIVE'),

    ('a0000000-0000-0000-0000-000000000004', 'Mohamed Kamara', 'bank.makeni@blooddemo.example.com',
     '$2a$10$m30ekhcbhvl1mwZKF02fG.YIfa7THcm.bCWYGDiag4kRX6vuWSo/K', 'BLOOD_BANK_STAFF',
     '+232 76 900 004', '11111111-1111-1111-1111-111111111111', null, 'ACTIVE'),

    ('a0000000-0000-0000-0000-000000000005', 'Isata Koroma', 'bank.holyspirit@blooddemo.example.com',
     '$2a$10$m30ekhcbhvl1mwZKF02fG.YIfa7THcm.bCWYGDiag4kRX6vuWSo/K', 'BLOOD_BANK_STAFF',
     '+232 76 900 005', '22222222-2222-2222-2222-222222222222', null, 'ACTIVE'),

    -- A single named showcase donor, distinct from the ~850 bulk accounts
    -- V901 generates — "Rahim Barrie" matches the name already used in the
    -- DonorPortal mockup fixture, so a first login looks exactly like the
    -- dissertation's own screenshots rather than an anonymous demo row.
    ('a0000000-0000-0000-0000-000000000006', 'Rahim Barrie', 'rahim.barrie@blooddemo.example.com',
     '$2a$10$m30ekhcbhvl1mwZKF02fG.YIfa7THcm.bCWYGDiag4kRX6vuWSo/K', 'DONOR',
     '+232 76 900 006', null, null, 'ACTIVE');

-- The showcase donor's profile. O- ("Universal Donor") matches the mockup's
-- own badge; a last_donation_date well past 90 days keeps them ELIGIBLE the
-- moment anyone logs in as them, which is the more inviting default state
-- for a demo login than showing up freshly ineligible.
insert into donors (id, user_id, blood_type, date_of_birth, gender, last_donation_date, address) values
    ('a0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000006',
     'O-', '1996-05-18', 'MALE', current_date - interval '150 days', 'Wusum, Makeni');

-- A real Donation row to back that last_donation_date, at the same date —
-- this is the one donor account someone giving a demo is most likely to log
-- in as directly, so its Donation History panel and "Total Donations" /
-- "Lives Impacted" stats need to show something real rather than sitting
-- empty under a profile that otherwise claims a donation history exists.
insert into donations (donor_id, blood_bank_id, donation_date, units_donated, blood_type) values
    ('a0000000-0000-0000-0000-000000000007', '11111111-1111-1111-1111-111111111111',
     current_date - interval '150 days', 1, 'O-');
