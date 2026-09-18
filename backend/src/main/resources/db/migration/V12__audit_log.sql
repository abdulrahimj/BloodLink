-- The administrators' audit log — Business Rule 17.
--
-- One row per significant administrator action: an account created, edited,
-- suspended or reactivated. Who did it, what kind of action, which account it
-- was done to, what changed, and when.
--
-- Append-only, and enforced here in the database rather than only by the
-- application having no code path that edits or deletes a row. The application
-- side is covered too (no endpoint, a repository with no update or delete
-- methods, and an entity Hibernate treats as immutable), but an audit log is
-- exactly the table someone with database access might be tempted to "tidy",
-- and the point of it is that nobody can quietly rewrite what happened —
-- including through a psql session, a future bug, or a well-meaning cleanup
-- script. The triggers below make every one of those fail loudly.

create table audit_log (
    id             uuid primary key default gen_random_uuid(),

    -- The administrator who acted. No ON DELETE clause, deliberately: the
    -- default (NO ACTION) means a user who appears in the log can never be
    -- deleted out from under it. Nothing in the application deletes users today;
    -- this keeps it that way for anyone who has taken an audited action.
    actor_user_id  uuid not null references users (id),

    action         varchar(32) not null
        check (action in ('ACCOUNT_CREATED', 'ACCOUNT_EDITED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED')),

    -- The account acted upon. Same reasoning as actor_user_id.
    target_user_id uuid not null references users (id),

    -- What changed, written for a person to read ("Phone changed from ... to
    -- ..."). A snapshot taken at the moment of the action, so the entry keeps
    -- saying what was true then even after the account changes again later.
    -- Never contains a password, in any form.
    details        text not null,

    created_at     timestamp with time zone not null default now()
);

-- The Audit Log page lists newest first.
create index idx_audit_log_created_at on audit_log (created_at desc);
-- "Everything that was done to this account", should anyone need to ask.
create index idx_audit_log_target on audit_log (target_user_id);

-- Refuses every attempt to change or remove a row. One function serves both
-- triggers; TG_OP names the operation that was attempted.
create function audit_log_is_append_only() returns trigger as $fn$
begin
    raise exception 'audit_log is append-only: % is not permitted (Business Rule 17)', TG_OP
        using errcode = 'insufficient_privilege';
end;
$fn$ language plpgsql;

-- Row-level: fires for any UPDATE or DELETE that touches at least one row.
create trigger audit_log_no_update_or_delete
    before update or delete on audit_log
    for each row execute function audit_log_is_append_only();

-- Statement-level: TRUNCATE does not fire row triggers at all, so without this
-- it would be a way to empty the table in one statement.
create trigger audit_log_no_truncate
    before truncate on audit_log
    for each statement execute function audit_log_is_append_only();

-- What this does NOT stop: a database superuser deliberately dropping the
-- triggers first. Nothing inside the database can prevent its own owner from
-- doing that; what the triggers guarantee is that it can never happen by
-- accident, or through the application.
