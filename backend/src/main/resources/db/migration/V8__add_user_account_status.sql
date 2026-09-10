-- Adds an active/suspended flag to user accounts.
--
-- Not part of the original CLAUDE.md schema table — added for the Admin module's
-- "activate or suspend a user account" use case, which this migration exists to
-- serve directly (the same reasoning V5 and V7 gave for adding blood_bank_id and
-- hospital_id: a real requirement the fixed schema had no column for).
--
-- Every existing row defaults to ACTIVE, so this migration cannot lock anyone out
-- of an account that already works. SUSPENDED is reached only through an
-- administrator's own action from this point on.
alter table users
    add column status varchar(16) not null default 'ACTIVE'
        check (status in ('ACTIVE', 'SUSPENDED'));
