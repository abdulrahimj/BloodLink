-- Donation history for a subset of the 850 donors V901 created.
--
-- Donors are ranked by their (unique) email in the order V901 generated them,
-- then split into three fixed bands so the counts are exact, not merely
-- likely: the first 511 donate at Makeni Regional only, the next 200 at Holy
-- Spirit only, and the remaining 139 never donate — staying ELIGIBLE with no
-- last_donation_date, same as a real donor who has signed up but not yet
-- given.
--
-- 511, not 512: V900's showcase donor (Rahim Barrie) also has a Donation row
-- at Makeni Regional, so 511 bulk donors plus that one showcase donor gives
-- Makeni Regional exactly 512 distinct donors overall — matching that bank's
-- "512 Registered Donors" mockup figure exactly (DonationRepository counts
-- *distinct donors who have donated at this bank*, regardless of which of
-- this project's several seed files put them there).
--
-- Dates are relative to current_date, mostly within the last ~180 days, so
-- the Admin Console's 6-month donation trend chart has real data in it
-- whenever this demo is actually started — a fixed historical date range
-- would look increasingly empty the further past it the demo runs.
--
-- Each donation's blood_type is copied from the donor's own profile value
-- rather than deliberately varied from it. Business Rule 4 allows the two to
-- differ (a donation records what was tested that day, not the profile), but
-- inventing a mismatch here would only look like an error to someone reading
-- this seed data without that context — this file favours internally
-- consistent demo data over exercising every rule it is possible to exercise.

insert into donations (donor_id, blood_bank_id, donation_date, units_donated, blood_type)
select
    d.id,
    case when ranked.rn <= 511 then '11111111-1111-1111-1111-111111111111'::uuid
         else '22222222-2222-2222-2222-222222222222'::uuid end,
    current_date - floor(random() * 180)::int,
    case when random() < 0.1 then 2 else 1 end,
    d.blood_type
from (
    select u.id as user_id, row_number() over (order by u.email) as rn
    from users u
    where u.email like 'donor%@blooddemo.example.com'
) ranked
join donors d on d.user_id = ranked.user_id
where ranked.rn <= 711; -- 511 + 200; the remaining 139 (712–850) get none

-- A second, earlier donation for a random ~120 of the 711 who gave once —
-- roughly one in six, enough for the donor history and donation-count
-- figures to show some donors with more than a single visit. Offset by
-- 120–270 days *before* the first donation, comfortably past Business Rule
-- 2's 90-day window, so no donor's own history implies they gave twice
-- inside their own recovery period.
insert into donations (donor_id, blood_bank_id, donation_date, units_donated, blood_type)
select
    d.donor_id, d.blood_bank_id,
    d.donation_date - (120 + floor(random() * 150))::int,
    1,
    d.blood_type
from donations d
where d.id in (select id from donations order by random() limit 120);

-- last_donation_date is derived from the donation rows just inserted, for
-- exactly the donors who received any — one fact computed from the other,
-- rather than two independently-guessed numbers that could disagree.
update donors d
set last_donation_date = donor_totals.most_recent
from (
    select donor_id, max(donation_date) as most_recent
    from donations
    group by donor_id
) donor_totals
where d.id = donor_totals.donor_id;
