-- Widen donors.gender to fit the value V2 added.
--
-- V2 extended the CHECK constraint to allow 'PREFER_NOT_TO_SAY', but the column was
-- declared varchar(16) in V1 and that label is 17 characters. The constraint accepted
-- the value while the column width rejected it, so every donor choosing "Prefer not to
-- say" on the Register screen failed to insert.
--
-- 32 matches users.role, the project's other string-backed enum column, and leaves
-- room for any further value without a third migration.
--
-- A separate migration rather than an edit to V2: V2 has already been applied, and
-- changing an applied migration breaks Flyway's checksum validation on startup.

alter table donors
    alter column gender type varchar(32);
