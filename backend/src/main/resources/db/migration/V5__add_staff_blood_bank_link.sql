-- Links a BLOOD_BANK_STAFF account to the one blood bank they work at.
--
-- Not part of the original CLAUDE.md schema table — added deliberately, after
-- confirming with the project owner, because nothing else in that schema lets
-- "manage own bank's inventory" (CLAUDE.md's own description of this role) be
-- enforced: Donor is the only role with a User link, and blood_banks has no
-- notion of its staff.
--
-- Nullable: DONOR, HOSPITAL_STAFF and ADMIN users never have a blood bank, and
-- ADMIN's access to inventory is by role, not by this column (see
-- InventoryService). Left unpopulated until an administrator user-management
-- phase exists to assign it.

alter table users
    add column blood_bank_id uuid references blood_banks (id);

create index idx_users_blood_bank on users (blood_bank_id);
