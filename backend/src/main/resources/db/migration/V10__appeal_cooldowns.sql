-- Per-(bank, blood group) donation appeal cooldowns — Business Rule 12.
--
-- The problem: the Send Appeal button on the Blood Inventory screen was derived
-- from the stock badge alone, so it looked identical whether the bank had last
-- appealed for that group ten minutes ago or never. Nothing recorded that an
-- appeal had gone out, so nothing could stop the same donors being asked again
-- and again about one unresolved shortage — and donor goodwill is the scarcest
-- thing this system spends.
--
-- The fix: record the last send per (bank, group), with the tier it went out
-- under. That is enough to answer "may this bank appeal for this group right
-- now?" without reading a single past notification.
--
-- Deliberately current state, not history: one row per pair, overwritten on each
-- send. Nothing in this project reads past appeals (an appeal lives on as
-- Notification rows in each recipient's bell), and only the most recent send can
-- answer the only question ever asked here.
--
-- This changes nothing about WHO receives an appeal. Business Rule 2's 90-day
-- donor-eligibility gate is untouched and still the only thing deciding that;
-- this table governs only whether the *button* may be pressed at all.

create table appeal_cooldowns (
    id             uuid primary key default gen_random_uuid(),
    blood_bank_id  uuid not null references blood_banks (id) on delete cascade,
    blood_type     varchar(3) not null
        check (blood_type in ('A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-')),
    last_sent_at   timestamp with time zone not null default now(),

    -- How short the bank was when the appeal went out: CRITICAL below 15 units,
    -- LOW from 15 to 29. Stored rather than re-derived from current stock,
    -- because it fixes this cooldown's length (5h vs 24h) and the level stock
    -- must reach to end it early (15 vs 30). Deriving it later would let those
    -- both change retroactively every time stock moved.
    --
    -- Note these are the *appeal* thresholds, not the Business Rule 3 badge
    -- thresholds (15/40) — see AppealTier for why the two differ on purpose.
    triggered_tier varchar(16) not null
        check (triggered_tier in ('CRITICAL', 'LOW')),

    -- One cooldown per pair. Also the guard that makes the send path's
    -- write-or-update safe against two staff at the same bank sending for the
    -- same group at the same instant — the second is a conflict, not a second row
    -- that would leave two disagreeing cooldowns for one button to read.
    constraint uq_appeal_cooldown_bank_type unique (blood_bank_id, blood_type)
);

-- The read path loads every cooldown for one bank at once (at most eight rows,
-- one per group) to render the inventory table, so the bank is the lookup key.
create index idx_appeal_cooldowns_bank on appeal_cooldowns (blood_bank_id);

-- on delete cascade: a cooldown is a fact about a bank and has no meaning once
-- that bank is gone, matching how V9's decline rows are scoped to their own
-- parents.
