-- ~850 donor accounts, matching the "850" and "850+" figures already shown on
-- the Admin Console and Login screen mockups (Chapter Three). Names are drawn
-- from common Sierra Leonean given and family names and combined by index, so
-- 850 rows read as varied people rather than 850 copies of "Donor N" — this is
-- demo data for screenshots and a live walkthrough, not a claim about real
-- residents of Makeni.
--
-- Blood-group distribution is an approximate West African population spread
-- (O and Rh-positive dominant), picked so the counts below sum to exactly 850:
--   O+ 382 · A+ 187 · B+ 127 · O- 51 · AB+ 34 · A- 34 · B- 25 · AB- 10
-- Assigned by a fixed range over the generated row number rather than randomly,
-- so the total for each group is exact and reproducible, not merely "close" —
-- worth knowing if this file is ever re-run against a database that already
-- has other donors in it and the totals look off by whatever was already there.
--
-- last_donation_date is left null for every row here (never donated yet).
-- V903 gives a subset of these donors real Donation rows and updates this
-- column to match — keeping "have they donated" and "when" as one fact
-- derived in one place, rather than two independently-guessed numbers that
-- could disagree.

insert into users (id, full_name, email, password_hash, role, phone, status)
select
    gen_random_uuid(),
    (array['Aminata','Mohamed','Fatmata','Ibrahim','Isatu','Alusine','Mariatu','Abdul','Adama','Foday',
           'Kadiatu','Sorie','Hawa','Alimamy','Yeanoh','Sallay','Umaru','Zainab','Bockarie','Marie',
           'Amara','Kumba','Santigie','Fanta','Mustapha','Ramatulai','Sahr','Isata','Momoh','Aisha'
    ])[((gs.n - 1) % 30) + 1]
    || ' ' ||
    (array['Kamara','Sesay','Bangura','Koroma','Turay','Conteh','Jalloh','Kargbo','Mansaray','Kanu',
           'Sankoh','Fofanah','Bah','Sillah','Tarawally','Kabba','Sheriff','Yillah','Vandi','Kondeh'
    ])[((gs.n - 1) % 20) + 1],
    'donor' || lpad(gs.n::text, 4, '0') || '@blooddemo.example.com',
    '$2a$10$m30ekhcbhvl1mwZKF02fG.YIfa7THcm.bCWYGDiag4kRX6vuWSo/K', -- BloodLink@2026, same as every demo account
    'DONOR',
    '+232 77 ' || lpad(gs.n::text, 4, '0'),
    'ACTIVE'
from generate_series(1, 850) as gs(n);

insert into donors (id, user_id, blood_type, date_of_birth, gender, last_donation_date, address)
select
    gen_random_uuid(),
    u.id,
    case
        when gs.n <= 382 then 'O+'
        when gs.n <= 569 then 'A+'   -- 382 + 187
        when gs.n <= 696 then 'B+'   -- 569 + 127
        when gs.n <= 747 then 'O-'   -- 696 + 51
        when gs.n <= 781 then 'AB+'  -- 747 + 34
        when gs.n <= 815 then 'A-'   -- 781 + 34
        when gs.n <= 840 then 'B-'   -- 815 + 25
        else 'AB-'                   -- 840 + 10 = 850
    end,
    -- Ages roughly 18–65: 6570 days is 18 years, plus up to 17155 more (~47 years).
    current_date - (6570 + floor(random() * 17155))::int,
    case
        when gs.n % 25 = 0 then 'PREFER_NOT_TO_SAY'
        when gs.n % 2 = 0 then 'MALE'
        else 'FEMALE'
    end,
    null,
    (array['Wusum, Makeni', 'Rogbaneh, Makeni', 'Kroo Bay Road, Makeni', 'Teko Road, Makeni',
           'Stadium Road, Makeni', 'Kandeh Town, Makeni', 'Kolisokor, Makeni', 'Kortimaw Road, Makeni'
    ])[((gs.n - 1) % 8) + 1]
from generate_series(1, 850) as gs(n)
join users u on u.email = 'donor' || lpad(gs.n::text, 4, '0') || '@blooddemo.example.com';
