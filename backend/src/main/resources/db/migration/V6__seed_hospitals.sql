-- Seeds the two hospitals named in CLAUDE.md as fixed reference data —
-- "Seeded, not self-registering", exactly as V4 did for the blood banks.
-- There is no registration endpoint for Hospital and there never will be;
-- these two rows are the whole table.
--
-- Fixed (not gen_random_uuid()) ids so the seed is stable across environments:
-- application code, tests, and anyone reading the database by hand can all
-- refer to "the Makeni Regional Hospital row" by the same literal id. The
-- 3.../4... pattern continues V4's 1.../2... for the blood banks, so a raw
-- id in a log line is recognisable as a hospital at a glance.

insert into hospitals (id, name, location, contact_phone) values
    ('33333333-3333-3333-3333-333333333333', 'Makeni Regional Hospital', 'Makeni City', '+232 76 200 001'),
    ('44444444-4444-4444-4444-444444444444', 'Holy Spirit Hospital',     'Makeni City', '+232 76 200 002');
