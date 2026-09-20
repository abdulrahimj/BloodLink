-- Partial, multi-bank fulfilment of hospital blood requests — Business Rule 20.
--
-- The problem this replaces: one blood bank claimed a whole request by approving
-- it, the request left every other bank's queue, and it was then fulfilled in a
-- single all-or-nothing step. A request for 4 units that no single bank could
-- cover therefore could not be filled at all, even when two banks holding 2 units
-- each could have filled it together.
--
-- The model that replaces it: any bank with stock contributes whatever units it
-- can, as often as it likes, until the requested total is met. Each contribution
-- is a row in the new table below, which is the source of truth for who gave what.
-- The request's status follows from the arithmetic: still short means
-- PARTIALLY_FULFILLED, nothing outstanding means FULFILLED.
--
-- Four things happen here, in order: the new table, the history already in this
-- database converted into it, the status vocabulary updated, and the notification
-- vocabulary updated.

-- ------------------------------------------------------------------
-- 1. One row per bank's contribution to one request
-- ------------------------------------------------------------------

create table blood_request_contributions (
    id                 uuid primary key default gen_random_uuid(),
    blood_request_id   uuid not null references blood_requests (id) on delete cascade,
    blood_bank_id      uuid not null references blood_banks (id)    on delete cascade,

    -- Units this bank issued in this one act. Always positive: a contribution of
    -- zero is not a contribution, and a negative one would be a return, which
    -- this system has no concept of.
    units_contributed  integer not null check (units_contributed > 0),

    contributed_at     timestamp with time zone not null default now()
);

-- Deliberately NO unique constraint on (request, bank), which is the one obvious
-- difference from blood_request_declines (V9). A bank declines a request at most
-- once — it is a single, final answer — but may contribute to the same request
-- several times as stock arrives, and each of those is a separate act with its own
-- units and its own timestamp.
--
-- There is also no constraint here capping the sum of a request's contributions at
-- units_requested, and that absence is deliberate rather than an oversight: a CHECK
-- cannot see other rows, and a trigger that summed them would still not stop two
-- simultaneous transactions (each would see only committed rows, both would pass,
-- and both would commit). The real guard is the pessimistic row lock RequestService
-- takes on the blood_requests row before reading the total — see Business Rule 20.
-- A trigger here would add the appearance of a guarantee without the guarantee.

-- "What has this request received so far" (every contribution path) and "what has
-- this bank issued" (the Units Issued figure on the Inventory screen).
create index idx_request_contributions_request on blood_request_contributions (blood_request_id);
create index idx_request_contributions_bank    on blood_request_contributions (blood_bank_id);

-- on delete cascade on both, for the same reason as V9's declines: a contribution
-- is a fact about a request and a bank, and means nothing once either is gone.

-- ------------------------------------------------------------------
-- 2. fulfilled_by_blood_bank_id, repurposed and renamed
-- ------------------------------------------------------------------

-- The column used to mean "the one bank that owns and will fill this request",
-- which is exactly the assumption this change removes: a request can now have
-- several contributors, and no single column can name them. Rather than leave a
-- column whose name asserts something untrue, it is renamed to what it now holds:
-- the bank that contributed most recently.
--
-- It is kept, rather than dropped, because it stays genuinely useful and is not a
-- second source of truth: the contributions table answers who gave what, and this
-- is a convenience pointer for the common display case ("2 of 4 units received,
-- from Makeni Regional Blood Bank") and for the notification wording. Anything that
-- needs totals, or the full list of contributors, reads the table.
--
-- The index idx_blood_requests_fulfiller (V1) follows the column automatically.
alter table blood_requests
    rename column fulfilled_by_blood_bank_id to last_contributed_by_blood_bank_id;

comment on column blood_requests.last_contributed_by_blood_bank_id is
    'The bank that most recently contributed units (Business Rule 20). A convenience pointer for display: '
    'blood_request_contributions is the source of truth for who contributed and how much.';

-- ------------------------------------------------------------------
-- 3. The history already in this database
-- ------------------------------------------------------------------

-- Every request already FULFILLED was filled entirely by the bank named on it, so
-- it becomes exactly one contribution of the full requested amount. Without this,
-- past fulfilments would vanish from the Units Issued figure and from each bank's
-- own queue, and the contributions table would be the source of truth for a
-- history it did not contain.
--
-- contributed_at is taken from created_at because the old lifecycle recorded no
-- fulfilment timestamp at all (the same approximation the Units Issued query was
-- already making). It is the request's own date, which is the closest honest value
-- available; nothing can recover the real one.
insert into blood_request_contributions (blood_request_id, blood_bank_id, units_contributed, contributed_at)
select id, last_contributed_by_blood_bank_id, units_requested, created_at
  from blood_requests
 where status = 'FULFILLED'
   and last_contributed_by_blood_bank_id is not null;

-- APPROVED is gone from the lifecycle: it meant "one bank has claimed this and will
-- fill it later", a promise that no longer exists now that a bank issues units at
-- the moment it acts. Such a request had nothing issued against it, so it returns
-- to the open pool as PENDING and loses its claiming bank — which is what the new
-- model would have it be: untouched, and available to every bank.
update blood_requests
   set status = 'PENDING',
       last_contributed_by_blood_bank_id = null
 where status = 'APPROVED';

-- ------------------------------------------------------------------
-- 4. The two vocabularies: request status, and notification type
-- ------------------------------------------------------------------

-- PARTIALLY_FULFILLED in, APPROVED out. The constraint name is PostgreSQL's
-- default for V1's inline column CHECK (<table>_<column>_check).
alter table blood_requests
    drop constraint blood_requests_status_check;
alter table blood_requests
    add constraint blood_requests_status_check
        check (status in ('PENDING', 'PARTIALLY_FULFILLED', 'FULFILLED', 'REJECTED'));

-- status is varchar(16) from V1 and 'PARTIALLY_FULFILLED' is 19 characters, so the
-- column has to grow or every partial contribution would fail on insert.
alter table blood_requests
    alter column status type varchar(24);

-- A fifth notification type: the remaining-units figure on a request changed.
-- It exists because contributions are now cooperative — without it, two banks can
-- each be looking at "4 units needed", both cover it, and one of them issues units
-- nobody needed. See Business Rule 20 for who receives it.
alter table notifications
    drop constraint notifications_type_check;
alter table notifications
    add constraint notifications_type_check
        check (type in ('LOW_STOCK_ALERT', 'DONATION_APPEAL', 'NEW_REQUEST',
                        'REQUEST_STATUS_CHANGE', 'REQUEST_PROGRESS'));
