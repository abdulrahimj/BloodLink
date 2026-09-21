-- Stores the demo accounts' phone numbers in the one format login compares
-- against (Business Rule 13): +232 followed by exactly eight digits, no spaces.
--
-- Why this is a separate file rather than a fix inside V900 and V901: those
-- migrations are already applied to demo databases that exist, and Flyway refuses
-- to start against a migration whose contents changed after it ran. Adding a new
-- migration keeps every earlier seed file byte-for-byte as it was.
--
-- Why it is needed at all: V11, which converts stored phones to this format, runs
-- at version 11 — before any demo row exists — so it never saw these accounts.
-- V900 wrote "+232 76 900 001", and login normalises whatever a person types to
-- "+23276900001", which does not match a value with spaces in it.
--
-- Demo data only: this file lives in db/demo-seed and is read solely by the demo
-- profile.

-- The ~850 generated donors (V901) were numbered "+232 77 0001" to "+232 77 0850":
-- only six digits after the country code, so removing the spaces alone would still
-- leave a number the system rejects. Padded to eight digits, in the 77 range, which
-- cannot collide with the 76 900 xxx staff numbers.
update users
   set phone = '+23277' || lpad(right(phone, 4), 6, '0')
 where phone ~ '^\+232 77 [0-9]{4}$';

-- Everything else (the six named accounts from V900) only needs its spaces removed.
update users
   set phone = regexp_replace(phone, '\s', '', 'g')
 where phone like '+232 %';
