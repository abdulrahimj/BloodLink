-- Makes email optional for donors — Business Rule 18.
--
-- Many voluntary donors in Makeni have a phone but no email address, and phone is
-- the login (Business Rule 13), so requiring an email turned some of them away at
-- registration for no reason. Staff and administrator accounts still need one
-- (Business Rule 14), and the second constraint below keeps that true in the
-- database, not only in the application.
--
-- No existing row changes: every account created before this had to have an email.

alter table users
    alter column email drop not null;

-- Uniqueness is untouched, and needs no special handling for accounts without an
-- email. V1's `unique` on this column (constraint users_email_key) follows standard
-- SQL NULL semantics — PostgreSQL's default NULLS DISTINCT — so NULL is never equal
-- to another NULL and any number of donors can have no email, while any two actual
-- addresses still cannot match. DonorOptionalEmailIT proves both halves directly.

-- "No email" is stored as NULL, never as an empty string. Two empty strings WOULD
-- collide on the unique index, so a blank value slipping in unnormalised would make
-- the second email-less donor fail to register. The application always converts
-- blank to NULL (EmailAddress.normalizeOptional); this makes that impossible to get
-- wrong from anywhere else, including a hand-written insert.
alter table users
    add constraint users_email_not_blank check (email is null or btrim(email) <> '');

-- Only donors may be without one.
alter table users
    add constraint users_email_required_for_staff check (role = 'DONOR' or email is not null);
