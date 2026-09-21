-- 50 blood requests spread across all four statuses of Business Rule 20's
-- lifecycle (Pending, Partially fulfilled, Fulfilled, Rejected), so the Hospital
-- Portal's tracking list and the Blood Bank Dashboard's incoming queue both have
-- something in every state on first login, not just PENDING.
--
-- Rewritten for Business Rule 20 (V15). It used to seed APPROVED requests and a
-- fulfilled_by_blood_bank_id column, neither of which exists any more, and V15
-- runs before this file on a fresh database. Partially fulfilled and Fulfilled
-- requests now carry real rows in blood_request_contributions, and a few
-- Fulfilled ones are split between both banks to show cooperative fulfilment.
-- (Like the rest of the demo seed, these rows record contributions without
-- deducting inventory: V902's stock is a fixed starting point.)
--
-- created_at is relative to now(), spread over roughly the last six weeks, so
-- the request feed looks recently active whenever this demo is actually
-- started rather than showing a fixed historical date range.
--
-- Partially fulfilled and Fulfilled requests are deliberately kept to modest
-- unit counts (2-6) against blood groups that are Sufficient at both banks in
-- V902's seeded inventory (O+, A+, B+, AB+) — so if anyone opens the real Blood
-- Bank Dashboard and contributes to a still-open one, it succeeds rather than
-- failing on stock it was never realistic to have. Pending and Rejected carry
-- no such constraint, since nothing is deducted for either — Pending in
-- particular includes a couple of requests against Makeni Regional's Critical
-- groups (B-, AB-) on purpose: that tension between "a hospital needs it" and
-- "the bank is nearly out" is exactly the scenario this system exists to make
-- visible.

-- ---------- PENDING (12) ----------
insert into blood_requests (hospital_id, blood_type, units_requested, urgency_level, status, patient_reference, created_at)
select
    case when gs.n % 2 = 0 then '33333333-3333-3333-3333-333333333333'::uuid else '44444444-4444-4444-4444-444444444444'::uuid end,
    (array['O+','A+','B+','O-','AB+','B-','AB-','A-','O+','B+','AB+','O-'])[gs.n],
    (array[2,3,1,4,2,3,1,2,5,3,2,4])[gs.n],
    (array['ROUTINE','URGENT','EMERGENCY','ROUTINE','URGENT','EMERGENCY','URGENT','ROUTINE','EMERGENCY','ROUTINE','URGENT','ROUTINE'])[gs.n],
    'PENDING',
    (array['Maternity Ward — Bed 3','Emergency Ward — Bed 7',null,'ICU — Bed 1','Maternity Ward — Bed 9',
           'Paediatric Ward — Bed 4',null,'General Ward — Bed 12','Emergency Ward — Bed 2','Surgical Ward — Bed 5',
           null,'Maternity Ward — Bed 1'])[gs.n],
    now() - (floor(random() * 40) || ' days')::interval
from generate_series(1, 12) as gs(n);

-- ---------- PARTIALLY_FULFILLED (10) ----------
insert into blood_requests (hospital_id, blood_type, units_requested, urgency_level, status, last_contributed_by_blood_bank_id, patient_reference, created_at)
select
    case when gs.n % 2 = 0 then '33333333-3333-3333-3333-333333333333'::uuid else '44444444-4444-4444-4444-444444444444'::uuid end,
    (array['O+','A+','B+','AB+'])[((gs.n - 1) % 4) + 1],
    (array[2,4,3,5,2,3,4,2,3,6])[gs.n],
    (array['URGENT','ROUTINE','EMERGENCY','ROUTINE','URGENT','ROUTINE','URGENT','EMERGENCY','ROUTINE','URGENT'])[gs.n],
    'PARTIALLY_FULFILLED',
    case when gs.n % 2 = 0 then '11111111-1111-1111-1111-111111111111'::uuid else '22222222-2222-2222-2222-222222222222'::uuid end,
    (array['ICU — Bed 3',null,'Surgical Ward — Bed 6','Maternity Ward — Bed 2','Emergency Ward — Bed 4',
           null,'General Ward — Bed 8','ICU — Bed 5','Maternity Ward — Bed 11','Paediatric Ward — Bed 1'])[gs.n],
    now() - (floor(random() * 20) || ' days')::interval
from generate_series(1, 10) as gs(n);

-- ---------- FULFILLED (20) ----------
insert into blood_requests (hospital_id, blood_type, units_requested, urgency_level, status, last_contributed_by_blood_bank_id, patient_reference, created_at)
select
    case when gs.n % 2 = 0 then '33333333-3333-3333-3333-333333333333'::uuid else '44444444-4444-4444-4444-444444444444'::uuid end,
    (array['O+','A+','B+','AB+'])[((gs.n - 1) % 4) + 1],
    2 + (gs.n % 5),
    (array['ROUTINE','URGENT','EMERGENCY'])[((gs.n - 1) % 3) + 1],
    'FULFILLED',
    case when gs.n % 2 = 0 then '11111111-1111-1111-1111-111111111111'::uuid else '22222222-2222-2222-2222-222222222222'::uuid end,
    case when gs.n % 3 = 0 then null else 'Ward ' || (gs.n % 12 + 1) || ' — Bed ' || (gs.n % 9 + 1) end,
    now() - (floor(random() * 45) || ' days')::interval
from generate_series(1, 20) as gs(n);

-- ---------- REJECTED (8) ----------
insert into blood_requests (hospital_id, blood_type, units_requested, urgency_level, status, patient_reference, created_at)
select
    case when gs.n % 2 = 0 then '33333333-3333-3333-3333-333333333333'::uuid else '44444444-4444-4444-4444-444444444444'::uuid end,
    (array['B-','AB-','A-','O-','B+','AB+','A+','O+'])[gs.n],
    (array[8,6,10,4,7,5,9,3])[gs.n],
    (array['EMERGENCY','URGENT','ROUTINE','EMERGENCY','URGENT','ROUTINE','URGENT','ROUTINE'])[gs.n],
    'REJECTED',
    (array['ICU — Bed 2',null,'Surgical Ward — Bed 1','Emergency Ward — Bed 9',null,
           'Maternity Ward — Bed 6','General Ward — Bed 3','ICU — Bed 4'])[gs.n],
    now() - (floor(random() * 35) || ' days')::interval
from generate_series(1, 8) as gs(n);

-- ---------- CONTRIBUTIONS (Business Rule 20) ----------

-- Each PARTIALLY_FULFILLED request has received about half of what it asked for
-- from the bank named on it — always at least 1 and always fewer than requested,
-- so units are genuinely still outstanding.
insert into blood_request_contributions (blood_request_id, blood_bank_id, units_contributed, contributed_at)
select id, last_contributed_by_blood_bank_id, greatest(1, units_requested / 2), created_at + interval '3 hours'
from blood_requests
where status = 'PARTIALLY_FULFILLED';

-- Small FULFILLED requests (under 4 units) were covered in full by the one bank
-- named on them.
insert into blood_request_contributions (blood_request_id, blood_bank_id, units_contributed, contributed_at)
select id, last_contributed_by_blood_bank_id, units_requested, created_at + interval '2 hours'
from blood_requests
where status = 'FULFILLED' and units_requested < 4;

-- Larger FULFILLED requests (4 units or more) were filled between BOTH banks: the
-- named bank gave the first half, the other bank finished the job.
insert into blood_request_contributions (blood_request_id, blood_bank_id, units_contributed, contributed_at)
select id, last_contributed_by_blood_bank_id, units_requested / 2, created_at + interval '2 hours'
from blood_requests
where status = 'FULFILLED' and units_requested >= 4;

insert into blood_request_contributions (blood_request_id, blood_bank_id, units_contributed, contributed_at)
select id,
       case when last_contributed_by_blood_bank_id = '11111111-1111-1111-1111-111111111111'::uuid
            then '22222222-2222-2222-2222-222222222222'::uuid
            else '11111111-1111-1111-1111-111111111111'::uuid end,
       units_requested - units_requested / 2,
       created_at + interval '5 hours'
from blood_requests
where status = 'FULFILLED' and units_requested >= 4;

-- The pointer follows the most recent contributor, so for the split ones it now
-- moves to the bank that finished the request.
update blood_requests
   set last_contributed_by_blood_bank_id = case
           when last_contributed_by_blood_bank_id = '11111111-1111-1111-1111-111111111111'::uuid
           then '22222222-2222-2222-2222-222222222222'::uuid
           else '11111111-1111-1111-1111-111111111111'::uuid end
 where status = 'FULFILLED' and units_requested >= 4;
