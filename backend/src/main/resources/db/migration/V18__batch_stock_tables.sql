-- Batch-level stock, Stage 1 of 6: the new tables, ADDITIVE ONLY.
--
-- Why: blood_inventory holds one row per (bank, blood group) with a single
-- expiry_date, so expired units are counted as stock everywhere, a fresh batch
-- inherits an older batch's date (Business Rule 7's "keep the earlier date"),
-- nothing can be discarded, and units cannot be issued earliest-expiry-first.
-- The replacement is one row per batch, each with its own expiry date.
--
-- What this migration does NOT do: it does not touch blood_inventory, copy any
-- data, or change any behaviour. Nothing in the application reads or writes
-- these tables yet. The data conversion and the rename of blood_inventory are
-- V19, shipped together with the code that reads the new tables, so that no
-- commit ever leaves the application reading one table while stock lives in
-- another. blood_inventory is dropped later still, by V20.
--
-- Indexes: only the two named in the design, each for a specific query. Every
-- index in V1-V17 was re-checked first; none covers these tables. The FK columns
-- written_off_by_user_id, batch_write_offs.batch_id and audit_log.target_batch_id
-- are deliberately left unindexed: no query reads by them on a hot path, the
-- tables are small, and an index nobody asked for is what V16/V17 had to undo.
--
-- Foreign keys use PostgreSQL's default NO ACTION throughout, unlike the
-- ON DELETE CASCADE that V9 and V15 used. These rows record real blood that
-- arrived, left, or was destroyed; a delete elsewhere must fail loudly rather
-- than make that record silently vanish. Nothing in the application deletes a
-- bank, user, donation, request or contribution today.

-- ------------------------------------------------------------------
-- 1. blood_inventory_batches: one row per batch of units
-- ------------------------------------------------------------------

create table blood_inventory_batches (
    id                uuid primary key default gen_random_uuid(),
    blood_bank_id     uuid not null references blood_banks (id),
    blood_type        varchar(3) not null
        check (blood_type in ('A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-')),

    -- How many units arrived in this batch, and how many are still on the shelf.
    -- units_received never changes (see the trigger below); units_remaining falls
    -- as units are issued to hospitals or written off.
    units_received    integer not null,
    units_remaining   integer not null,

    -- The last day the batch may be used, inclusive: usable through the whole of
    -- this date, expired from 00:00 UTC the next day. Fixed for the life of the
    -- batch, because issuing earliest-expiry-first is only trustworthy if the
    -- order it is issued in can never change underneath it. A wrong date is
    -- corrected by writing the batch off (DATA_ENTRY_ERROR) and re-adding it.
    --
    -- No CHECK that it is in the future: a CHECK cannot use today's date, and a
    -- batch converted from existing stock (V19) can legitimately be past expiry
    -- already. The application refuses a past date when a batch is entered.
    expiry_date       date not null,

    received_at       timestamp with time zone not null default now(),
    last_updated      timestamp with time zone not null default now(),

    -- The donation these units came from, for batches made by Record Donation.
    -- Unique: one donation produces exactly one batch. NULLs never collide, so
    -- any number of non-donation batches may leave it empty.
    donation_id       uuid references donations (id),

    -- Where the batch came from: a recorded donation, Add New Batch, or the V19
    -- conversion of blood_inventory (whose dates are the merged "earliest of"
    -- dates, so staff should check them against the bag labels).
    source            varchar(16) not null,

    -- When the expiry of this batch was noticed for alerting. Reserved for the
    -- future expiry-rollover job; nothing sets it yet.
    expiry_noticed_at timestamp with time zone,

    constraint chk_batches_units_received_positive
        check (units_received > 0),
    -- Never negative, and never more than arrived. This is defence in depth for
    -- the row locks: even if locking were ever wrong, the database would refuse to
    -- issue the same unit of blood twice.
    constraint chk_batches_units_remaining_in_range
        check (units_remaining >= 0 and units_remaining <= units_received),
    constraint chk_batches_source
        check (source in ('DONATION', 'MANUAL', 'MIGRATED')),
    -- A donation batch names its donation; no other batch does.
    constraint chk_batches_donation_matches_source
        check ((source = 'DONATION') = (donation_id is not null)),
    constraint uq_batches_donation unique (donation_id)
);

-- The FEFO index (first expiry, first out). Serves the three queries the stock
-- logic is built on: lock one bank's usable batches of one group in issue order
-- (earliest expiry, then id), sum a bank's usable units per group, and list a
-- bank's live batches. Partial on units_remaining > 0, so batches that have been
-- fully used stay in the table as history without growing the index.
create index idx_batches_fefo
    on blood_inventory_batches (blood_bank_id, blood_type, expiry_date, id)
    where units_remaining > 0;

-- Identity columns are fixed once a batch exists. units_remaining, last_updated
-- and expiry_noticed_at are the only columns that may change.
create function blood_inventory_batches_identity_is_immutable() returns trigger as $fn$
declare
    changed text;
begin
    if new.id is distinct from old.id then
        changed := 'id';
    elsif new.blood_bank_id is distinct from old.blood_bank_id then
        changed := 'blood_bank_id';
    elsif new.blood_type is distinct from old.blood_type then
        changed := 'blood_type';
    elsif new.units_received is distinct from old.units_received then
        changed := 'units_received';
    elsif new.expiry_date is distinct from old.expiry_date then
        changed := 'expiry_date';
    elsif new.received_at is distinct from old.received_at then
        changed := 'received_at';
    elsif new.donation_id is distinct from old.donation_id then
        changed := 'donation_id';
    elsif new.source is distinct from old.source then
        changed := 'source';
    end if;

    if changed is not null then
        raise exception 'blood_inventory_batches.% cannot be changed once a batch exists', changed
            using errcode = 'integrity_constraint_violation';
    end if;
    return new;
end;
$fn$ language plpgsql;

create trigger blood_inventory_batches_identity_fixed
    before update on blood_inventory_batches
    for each row execute function blood_inventory_batches_identity_is_immutable();

-- ------------------------------------------------------------------
-- 2. Append-only, for the two ledgers below
-- ------------------------------------------------------------------

-- The same refusal V12 gives audit_log, written once for any table that is a
-- ledger of what happened. TG_TABLE_NAME names the table in the message.
create function ledger_is_append_only() returns trigger as $fn$
begin
    raise exception '% is append-only: % is not permitted', TG_TABLE_NAME, TG_OP
        using errcode = 'insufficient_privilege';
end;
$fn$ language plpgsql;

-- ------------------------------------------------------------------
-- 3. contribution_allocations: which batches a contribution drew from
-- ------------------------------------------------------------------

-- One row per batch that supplied units to one contribution (traceability). The
-- allocations of a contribution add up to its units_contributed; a CHECK cannot
-- see other rows, so the contribution code enforces that and a test asserts it.
-- Contributions made before batch stock existed have no allocations.
create table contribution_allocations (
    id               uuid primary key default gen_random_uuid(),
    contribution_id  uuid not null references blood_request_contributions (id),
    batch_id         uuid not null references blood_inventory_batches (id),
    units_allocated  integer not null,

    constraint chk_allocations_units_positive check (units_allocated > 0),
    -- A contribution takes from a given batch once. Also serves "which batches
    -- did this contribution use", since contribution_id is its leading column.
    constraint uq_allocation_contribution_batch unique (contribution_id, batch_id)
);

-- "Where did this batch's units go": the batch drill-down.
create index idx_allocations_batch on contribution_allocations (batch_id);

create trigger contribution_allocations_no_update_or_delete
    before update or delete on contribution_allocations
    for each row execute function ledger_is_append_only();

create trigger contribution_allocations_no_truncate
    before truncate on contribution_allocations
    for each statement execute function ledger_is_append_only();

-- ------------------------------------------------------------------
-- 4. batch_write_offs: units discarded, and why
-- ------------------------------------------------------------------

create table batch_write_offs (
    id                      uuid primary key default gen_random_uuid(),
    batch_id                uuid not null references blood_inventory_batches (id),
    units                   integer not null,
    reason                  varchar(24) not null,
    -- Optional, except for OTHER. Blank is stored as NULL, never '' (the same
    -- rule users.email follows, V13).
    note                    text,
    written_off_by_user_id  uuid not null references users (id),
    written_off_at          timestamp with time zone not null default now(),

    constraint chk_write_offs_units_positive check (units > 0),
    constraint chk_write_offs_reason
        check (reason in ('EXPIRED', 'DAMAGED', 'FAILED_TESTING', 'DATA_ENTRY_ERROR', 'OTHER')),
    constraint chk_write_offs_note_not_blank check (note is null or btrim(note) <> ''),
    constraint chk_write_offs_note_length check (note is null or char_length(note) <= 500),
    constraint chk_write_offs_note_required_for_other check (reason <> 'OTHER' or note is not null)
);

create trigger batch_write_offs_no_update_or_delete
    before update or delete on batch_write_offs
    for each row execute function ledger_is_append_only();

create trigger batch_write_offs_no_truncate
    before truncate on batch_write_offs
    for each statement execute function ledger_is_append_only();

-- ------------------------------------------------------------------
-- 5. audit_log: a write-off is recorded, against the batch
-- ------------------------------------------------------------------

-- Existing entries are read here (to validate the new constraints), never changed,
-- so V12's append-only triggers are not involved, exactly as in V14.
alter table audit_log
    add column target_batch_id uuid references blood_inventory_batches (id);

alter table audit_log
    drop constraint audit_log_action_check;
alter table audit_log
    add constraint audit_log_action_check
        check (action in ('ACCOUNT_CREATED', 'ACCOUNT_EDITED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED',
                          'BLOOD_BANK_CREATED', 'HOSPITAL_CREATED', 'STOCK_WRITTEN_OFF'));

-- Still exactly one target per entry, and the one its action is about. The actor
-- of a STOCK_WRITTEN_OFF entry may be blood bank staff as well as an administrator.
alter table audit_log
    drop constraint audit_log_target_matches_action;
alter table audit_log
    add constraint audit_log_target_matches_action check (
        (action in ('ACCOUNT_CREATED', 'ACCOUNT_EDITED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED')
            and target_user_id is not null and target_blood_bank_id is null
            and target_hospital_id is null and target_batch_id is null)
     or (action = 'BLOOD_BANK_CREATED'
            and target_blood_bank_id is not null and target_user_id is null
            and target_hospital_id is null and target_batch_id is null)
     or (action = 'HOSPITAL_CREATED'
            and target_hospital_id is not null and target_user_id is null
            and target_blood_bank_id is null and target_batch_id is null)
     or (action = 'STOCK_WRITTEN_OFF'
            and target_batch_id is not null and target_user_id is null
            and target_blood_bank_id is null and target_hospital_id is null)
    );
