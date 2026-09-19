-- Administrator-created blood banks and hospitals — Business Rule 19.
--
-- Until now both tables held only their seeded rows (V4, V6), and a new facility
-- could only be added by hand in psql. POST /api/admin/blood-banks and
-- POST /api/admin/hospitals now do it, and this migration gives them the two
-- things the database has to guarantee for that to be safe:
--
--   1. A facility name is unique within its type, so two rows can never be the
--      same place — a staff picker or an inventory screen offering two identical
--      "Holy Spirit Blood Bank" entries would have no right answer.
--   2. The audit log (Business Rule 17, V12) can record a facility being created,
--      not only an account being acted on.
--
-- No existing row in either table changes.

-- ------------------------------------------------------------------
-- 1. Unique facility names
-- ------------------------------------------------------------------

-- Compared case-insensitively: "Holy Spirit Blood Bank" and "holy spirit blood
-- bank" are the same facility typed two ways, and a plain unique constraint would
-- let both in. The application also trims and collapses spaces before storing
-- (AdminFacilityService.normaliseName), so spacing cannot sneak a duplicate past
-- this either; together they mean the index sees one spelling per facility.
--
-- Checked first, and refused with a message a person can act on, rather than
-- letting CREATE UNIQUE INDEX fail with a bare "could not create unique index".
-- Nothing today inserts facilities except the seeds, so this should never fire —
-- but if someone did add a duplicate by hand, which of the two rows is the real one
-- (and which staff, stock and requests belong to it) is a question only a person
-- can answer, so the migration stops instead of guessing.
do $$
declare
    clash text;
begin
    select string_agg(name, ', ') into clash
      from (select min(name) as name from blood_banks group by lower(name) having count(*) > 1) d;
    if clash is not null then
        raise exception 'V14: blood_banks has more than one row named %; merge or rename them by hand, then re-run', clash;
    end if;

    select string_agg(name, ', ') into clash
      from (select min(name) as name from hospitals group by lower(name) having count(*) > 1) d;
    if clash is not null then
        raise exception 'V14: hospitals has more than one row named %; merge or rename them by hand, then re-run', clash;
    end if;
end
$$;

create unique index uq_blood_banks_name on blood_banks (lower(name));
create unique index uq_hospitals_name   on hospitals   (lower(name));

-- ------------------------------------------------------------------
-- 2. The audit log learns about facilities
-- ------------------------------------------------------------------

-- A facility creation has no account as its subject, so the account target is no
-- longer required for every row — and a facility needs a target of its own. Two
-- nullable columns, one per facility type, as real foreign keys rather than a
-- name in the details text, for the same reason target_user_id is one: the entry
-- points at the thing, not only at a description of it.
--
-- Neither ALTER below updates, deletes or truncates a row, so V12's append-only
-- triggers are not involved: existing entries are read (to validate the new
-- CHECK constraints), never changed.
alter table audit_log
    alter column target_user_id drop not null;

-- No ON DELETE clause, deliberately — the same NO ACTION default V12 gives both
-- user columns: a facility that appears in the log can never be deleted out from
-- under it. (Nothing deletes a facility today; Business Rule 19 is create-only.)
alter table audit_log
    add column target_blood_bank_id uuid references blood_banks (id),
    add column target_hospital_id   uuid references hospitals (id);

-- The two new action names, alongside V12's four. The name is PostgreSQL's
-- default for V12's inline column CHECK (<table>_<column>_check).
alter table audit_log
    drop constraint audit_log_action_check;
alter table audit_log
    add constraint audit_log_action_check
        check (action in ('ACCOUNT_CREATED', 'ACCOUNT_EDITED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED',
                          'BLOOD_BANK_CREATED', 'HOSPITAL_CREATED'));

-- Exactly one target per entry, and the one its action is about. Loosening the
-- NOT NULL above must not allow an entry that points at nothing, or an account
-- action that forgot its account — so this is the rule that replaces it.
alter table audit_log
    add constraint audit_log_target_matches_action check (
        (action in ('ACCOUNT_CREATED', 'ACCOUNT_EDITED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED')
            and target_user_id is not null and target_blood_bank_id is null and target_hospital_id is null)
     or (action = 'BLOOD_BANK_CREATED'
            and target_blood_bank_id is not null and target_user_id is null and target_hospital_id is null)
     or (action = 'HOSPITAL_CREATED'
            and target_hospital_id is not null and target_user_id is null and target_blood_bank_id is null)
    );