-- BloodLink — schema adjustments required by the authentication phase.
--
-- Three changes, each reconciling the V1 schema with the agreed Register/Login UI.
-- No new tables or columns are introduced; only constraints change.

-- 1. Phone is the login credential.
--    Login.jsx authenticates on phone + password, so a phone must identify exactly
--    one account. V1 left users.phone non-unique (only email was unique), which would
--    make "find the user for this phone" ambiguous.
alter table users
    add constraint uq_users_phone unique (phone);

-- 2. A donor may not know their blood group at sign-up.
--    Register.jsx offers "I don't know yet". This is consistent with Business Rule 4:
--    blood type is re-verified by testing at every donation, so the donor's
--    self-reported group was never the authoritative value. Null means "not yet
--    established"; it is populated once a tested Donation record exists.
alter table donors
    alter column blood_type drop not null;

-- 3. Gender gains a third value.
--    Register.jsx offers "Prefer not to say". PostgreSQL cannot extend a CHECK
--    constraint in place, so it is dropped and recreated. The constraint name is the
--    one PostgreSQL generated for the inline CHECK in V1 (<table>_<column>_check).
alter table donors
    drop constraint donors_gender_check;

alter table donors
    add constraint donors_gender_check
    check (gender in ('MALE', 'FEMALE', 'PREFER_NOT_TO_SAY'));
