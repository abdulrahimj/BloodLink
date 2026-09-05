-- Seeds the two blood banks named in CLAUDE.md as fixed reference data —
-- "Seeded, not self-registering". There is no registration endpoint for
-- BloodBank and there never will be; these two rows are the whole table.
--
-- Fixed (not gen_random_uuid()) ids so the seed is stable across environments:
-- application code, tests, and anyone reading the database by hand can all
-- refer to "the Makeni Regional Blood Bank row" by the same literal id.

insert into blood_banks (id, name, location, contact_phone) values
    ('11111111-1111-1111-1111-111111111111', 'Makeni Regional Blood Bank', 'Makeni City', '+232 76 100 001'),
    ('22222222-2222-2222-2222-222222222222', 'Holy Spirit Blood Bank',     'Makeni City', '+232 76 100 002');
