-- Converts every phone number already stored on a user account to the one format
-- the application now stores and compares: +232 followed by exactly eight digits,
-- no spaces and no leading zero (e.g. +23276900005).
--
-- Why this has to exist
-- ---------------------
-- Phone is the login credential (V2 made users.phone unique), and a credential is
-- matched by exact string equality. From this release on, PhoneNumber.normalize
-- runs over what the user types at registration AND at login, so the two always
-- agree. But rows written before that rule existed hold whatever was typed then —
-- "076900005", "+232 76 900 005", "+23276900005" — and a login normalised to
-- "+23276900005" matches only the last of those. Without this migration, every
-- account created before today whose number was not already in that exact form
-- would stop being able to sign in. That includes the project's own test accounts.
--
-- This runs once, at the next application start. Flyway records it and never
-- repeats it.
--
-- Data safety (CLAUDE.md)
-- -----------------------
-- This migration writes to real rows in bloodlink_dev, which is unavoidable —
-- rewriting stored values is the entire point of it. It is still built to be as
-- conservative as that allows:
--   * It DELETES nothing, and creates and drops nothing permanent.
--   * It touches only users.phone, and only rows whose value actually changes.
--   * A value it cannot confidently convert is LEFT EXACTLY AS IT IS, never
--     guessed at, and reported as a warning in the application log.
--   * It refuses to run at all if converting would make two accounts share a
--     number, rather than letting the unique constraint fail halfway through.
-- Take a pg_dump of bloodlink_dev before the first start that applies this, the
-- same as for any other manual write to that database.
--
-- Deliberately NOT touched: blood_banks.contact_phone and hospitals.contact_phone.
-- Those are display-only facility numbers shown on a contact card — nothing
-- compares them to anything, nothing logs in with them, and no rule in the system
-- depends on their shape. Rewriting live rows that nothing reads by value would be
-- risk with no benefit. If they should match this format too, that is a separate,
-- deliberate decision and a separate migration.

-- The rule, in SQL, mirroring PhoneNumber.normalize step for step. Created in
-- pg_temp so it exists only for this session: Flyway needs it for the three
-- statements below, and nothing afterwards should be able to call a second,
-- drifting copy of a rule that lives in Java. Returns null for "this is not a
-- number I can convert", which every caller below treats as "leave it alone".
create function pg_temp.normalize_phone(raw text) returns text as $fn$
declare
    compact text;
begin
    -- 1. Strip all whitespace, whichever format it was entered in.
    compact := regexp_replace(coalesce(raw, ''), '\s', '', 'g');

    -- 2. Digits only, with at most a single leading "+". Anything else (a letter,
    --    a hyphen, brackets) is refused rather than stripped — same as in Java.
    if compact !~ '^\+?[0-9]+$' then
        return null;
    end if;

    -- 3. A leading 0 is the local trunk prefix: replaced by the country code.
    if left(compact, 1) = '0' then
        compact := '+232' || substr(compact, 2);
    end if;

    -- 4. The length gate: the result must be +232 plus exactly eight digits.
    if compact !~ '^\+232[0-9]{8}$' then
        return null;
    end if;

    return compact;
end;
$fn$ language plpgsql immutable;

-- Stop before writing anything if two accounts would end up sharing one number —
-- for example "076900005" and "+232 76 900 005" both already present as separate
-- users. uq_users_phone would refuse the second update anyway; failing here
-- instead means the error names the numbers involved and the update never starts,
-- rather than a constraint violation arriving mid-statement. Two accounts for one
-- phone is a data question only a person can answer (which one is the real
-- account?), so this asks rather than picks.
do $collisions$
declare
    colliding text;
begin
    select string_agg(format('%s (%s accounts)', normalized, account_count), ', ')
      into colliding
      from (
          select pg_temp.normalize_phone(phone) as normalized,
                 count(*)                       as account_count
            from users
           where pg_temp.normalize_phone(phone) is not null
           group by 1
          having count(*) > 1
      ) duplicates;

    if colliding is not null then
        raise exception
            'V11 stopped without changing anything: normalising would make these numbers duplicates: %. '
            'Decide which account keeps each number and change or remove the other, then start again.',
            colliding;
    end if;
end;
$collisions$;

-- The conversion itself. Only rows that are convertible and not already correct
-- are written, so a database that is already clean is updated zero times.
update users
   set phone = pg_temp.normalize_phone(phone)
 where pg_temp.normalize_phone(phone) is not null
   and pg_temp.normalize_phone(phone) <> phone;

-- Anything left behind is a stored value that is not a Sierra Leone number at all
-- (wrong length, a foreign country code, characters that do not belong in a phone
-- number). Those rows are left untouched on purpose — inventing the missing digit
-- would be changing someone's phone number to one this system guessed. They are
-- surfaced as a warning in the startup log because the owner needs to know: until
-- one of these is corrected, that account cannot sign in, since nothing a person
-- can type will normalise to it. Fixing one is a single update to that row.
do $leftovers$
declare
    leftovers text;
begin
    select string_agg(format('%s (%s)', phone, full_name), ', ')
      into leftovers
      from users
     where pg_temp.normalize_phone(phone) is null;

    if leftovers is not null then
        raise warning
            'V11: these stored phone numbers are not valid Sierra Leone numbers and were left unchanged: %. '
            'Each of these accounts cannot sign in until its number is corrected.',
            leftovers;
    end if;
end;
$leftovers$;
