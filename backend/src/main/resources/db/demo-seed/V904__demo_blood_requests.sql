-- 50 blood requests spread across all four Business Rule 5 statuses, so the
-- Hospital Portal's tracking list and the Blood Bank Dashboard's incoming
-- queue both have something in every state on first login, not just PENDING.
--
-- created_at is relative to now(), spread over roughly the last six weeks, so
-- the request feed looks recently active whenever this demo is actually
-- started rather than showing a fixed historical date range.
--
-- Approved and Fulfilled requests are deliberately kept to modest unit counts
-- (2–6) against blood groups that are Sufficient at both banks in V902's
-- seeded inventory (O+, A+, B+, AB+) — so if anyone opens the real Blood Bank
-- Dashboard and clicks Fulfil on a still-Approved one of these, it succeeds
-- rather than failing on stock it was never realistic to have claimed.
-- Pending and Rejected carry no such constraint, since nothing is deducted
-- for either — Pending in particular includes a couple of requests against
-- Makeni Regional's Critical groups (B-, AB-) on purpose: that tension
-- between "a hospital needs it" and "the bank is nearly out" is exactly the
-- scenario this system exists to make visible.

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

-- ---------- APPROVED (10) ----------
insert into blood_requests (hospital_id, blood_type, units_requested, urgency_level, status, fulfilled_by_blood_bank_id, patient_reference, created_at)
select
    case when gs.n % 2 = 0 then '33333333-3333-3333-3333-333333333333'::uuid else '44444444-4444-4444-4444-444444444444'::uuid end,
    (array['O+','A+','B+','AB+'])[((gs.n - 1) % 4) + 1],
    (array[2,4,3,5,2,3,4,2,3,6])[gs.n],
    (array['URGENT','ROUTINE','EMERGENCY','ROUTINE','URGENT','ROUTINE','URGENT','EMERGENCY','ROUTINE','URGENT'])[gs.n],
    'APPROVED',
    case when gs.n % 2 = 0 then '11111111-1111-1111-1111-111111111111'::uuid else '22222222-2222-2222-2222-222222222222'::uuid end,
    (array['ICU — Bed 3',null,'Surgical Ward — Bed 6','Maternity Ward — Bed 2','Emergency Ward — Bed 4',
           null,'General Ward — Bed 8','ICU — Bed 5','Maternity Ward — Bed 11','Paediatric Ward — Bed 1'])[gs.n],
    now() - (floor(random() * 20) || ' days')::interval
from generate_series(1, 10) as gs(n);

-- ---------- FULFILLED (20) ----------
insert into blood_requests (hospital_id, blood_type, units_requested, urgency_level, status, fulfilled_by_blood_bank_id, patient_reference, created_at)
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
