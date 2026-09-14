-- Per-bank rejection of hospital blood requests — the fix for the problem
-- CLAUDE.md's Business Rule 11 flagged for review.
--
-- The problem: blood_requests.status is a single shared field with one REJECTED
-- state, so one blood bank turning a request down closed it for every bank, even
-- when another bank had the stock to fulfil it. A hospital could be left with a
-- dead request purely because the first bank to look at it happened to be out.
--
-- The fix, exactly as Business Rule 11 anticipated it would need to be: a
-- separate per-bank response table. One row per (request, bank) pair meaning
-- "this bank has declined this request". blood_requests.status is NOT touched
-- when a single bank declines — the request stays PENDING and stays actionable
-- for every bank that has not yet responded. It only becomes REJECTED once every
-- blood bank has declined it, which the application decides by comparing the
-- number of declines on a request against the number of blood banks that exist.
--
-- Why a table rather than, say, a list of bank ids on blood_requests: the unique
-- constraint below is what makes "has this bank already declined?" a fact the
-- database enforces rather than something the application has to remember to
-- check. Two staff at the same bank clicking Reject at the same moment cannot
-- produce two decline rows, so the count this feature depends on can never be
-- inflated into a premature REJECTED.

create table blood_request_declines (
    id               uuid primary key default gen_random_uuid(),
    blood_request_id uuid not null references blood_requests (id) on delete cascade,
    blood_bank_id    uuid not null references blood_banks (id)    on delete cascade,
    declined_at      timestamp with time zone not null default now(),

    -- A bank declines a given request once. Also the guard described above
    -- against a double-click or two operators producing two rows, which would
    -- make the "have all banks declined?" count wrong.
    constraint uq_decline_request_bank unique (blood_request_id, blood_bank_id)
);

-- Both directions are read on the hot path: the dashboard query asks "which
-- requests has THIS bank declined" (to hide them from its queue), and the
-- reject action asks "how many banks have declined THIS request" (to decide
-- whether that was the last one).
create index idx_request_declines_request on blood_request_declines (blood_request_id);
create index idx_request_declines_bank    on blood_request_declines (blood_bank_id);

-- on delete cascade on both foreign keys, deliberately: a decline is a fact
-- *about* a request and a bank, with no meaning once either is gone. Leaving
-- orphaned rows behind would also corrupt the count that decides REJECTED —
-- a decline by a blood bank that no longer exists would still count towards
-- "every bank has declined it".
