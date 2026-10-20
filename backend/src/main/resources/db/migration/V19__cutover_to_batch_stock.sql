-- Batch-level stock, Stage 2 of 6: the cutover.
--
-- Every row of the old one-row-per-(bank, group) blood_inventory that holds stock
-- becomes one batch in blood_inventory_batches (V18), and the old table is renamed
-- to blood_inventory_pre_batches so nothing can read or write it by mistake. It is
-- kept, read by nothing, as an in-database record of what was converted; V20 drops
-- it later. Shipped together with the code that reads the new table, so the
-- application never reads one table while its stock lives in the other.
--
-- Flyway runs this whole file as ONE transaction (PostgreSQL has transactional
-- DDL): if any check below fails, every statement is undone and the database is
-- left exactly as it was, at V18.
--
-- What converting a row means, and what it cannot recover:
--   * The new batch REUSES the old row's id, so anything that ever recorded that id
--     still points at the same stock.
--   * units_received = units_remaining = the old units_available. The old table
--     kept no history, so the batch starts from what was on the shelf.
--   * received_at and last_updated = the old last_updated, the closest honest
--     value: the old table never recorded when stock arrived. The Inventory
--     screen's "Last Updated" therefore shows the same moment it did before.
--   * expiry_date = the old expiry_date. Under the old Business Rule 7 that was the
--     EARLIEST date of every batch ever merged into the row, so it is conservative:
--     it can make good units look as if they expire sooner, never later. The real
--     per-batch split is not recoverable. Staff should check MIGRATED batches
--     against the bag labels.
--   * A row with 0 units becomes NO batch. A batch of nothing cannot exist (V18's
--     CHECK), and that row's expiry date described no stock at all. Each one is
--     listed in the migration output, and stays visible in the renamed table.
--   * A row already past its expiry date becomes an already-expired batch: it
--     counts as zero usable units from now on. It is stamped expiry_noticed_at, so
--     no future expiry alert ever fires for an expiry that happened before batches
--     existed.

-- ------------------------------------------------------------------
-- 1. Pre-flight: refuse to run on data that cannot be converted
-- ------------------------------------------------------------------

-- Checked before anything is written, and refused with a message a person can act
-- on, in the same style as V11 and V14. Neither should ever fire.
do $$
declare
    existing  bigint;
    negatives text;
begin
    -- V19 must be the first and only writer into the new table. If something has
    -- already put batches there, converting on top would count stock twice.
    select count(*) into existing from blood_inventory_batches;
    if existing > 0 then
        raise exception 'V19: blood_inventory_batches already holds % row(s), but nothing should have written to it before this migration. Find out what did before converting, or stock would be counted twice', existing;
    end if;

    -- blood_inventory never had a CHECK on units, so a negative count is possible
    -- if someone edited the table by hand. It cannot become a batch, and what the
    -- right figure is can only be answered by a person.
    select string_agg(format('%s %s (row %s): %s units', b.name, i.blood_type, i.id, i.units_available),
                      '; ' order by b.name, i.blood_type)
      into negatives
      from blood_inventory i
      join blood_banks b on b.id = i.blood_bank_id
     where i.units_available < 0;
    if negatives is not null then
        raise exception 'V19: these blood_inventory rows hold a negative number of units and cannot become batches; correct them by hand, then re-run: %', negatives;
    end if;
end
$$;

-- ------------------------------------------------------------------
-- 2. The conversion
-- ------------------------------------------------------------------

insert into blood_inventory_batches (
    id, blood_bank_id, blood_type, units_received, units_remaining,
    expiry_date, received_at, last_updated, source, expiry_noticed_at
)
select i.id, i.blood_bank_id, i.blood_type, i.units_available, i.units_available,
       i.expiry_date, i.last_updated, i.last_updated, 'MIGRATED',
       -- Expired = expiry date before today in UTC (StockPolicy): usable through the
       -- whole of the expiry date, expired from 00:00 UTC the next day.
       case when i.expiry_date < (now() at time zone 'utc')::date then now() end
  from blood_inventory i
 where i.units_available > 0;

-- ------------------------------------------------------------------
-- 3. Verification: abort the whole migration on any mismatch
-- ------------------------------------------------------------------

-- Per bank and blood group, the converted batches must hold exactly the units the
-- old rows held, in the same number of rows; and row by row, every old row with
-- stock must have a batch with its id, bank, group, units and expiry date.
do $$
declare
    totals_mismatch text;
    rows_mismatch   text;
begin
    with legacy as (
        select blood_bank_id, blood_type, sum(units_available) as units, count(*) as n
          from blood_inventory
         where units_available > 0
         group by blood_bank_id, blood_type
    ), converted as (
        select blood_bank_id, blood_type, sum(units_remaining) as remaining,
               sum(units_received) as received, count(*) as n
          from blood_inventory_batches
         where source = 'MIGRATED'
         group by blood_bank_id, blood_type
    )
    select string_agg(format('bank %s %s: legacy %s units in %s row(s), batches %s remaining / %s received in %s',
                             coalesce(l.blood_bank_id, c.blood_bank_id), coalesce(l.blood_type, c.blood_type),
                             l.units, l.n, c.remaining, c.received, c.n), '; ')
      into totals_mismatch
      from legacy l
      full join converted c on c.blood_bank_id = l.blood_bank_id and c.blood_type = l.blood_type
     where l.units is distinct from c.remaining
        or l.units is distinct from c.received
        or l.n     is distinct from c.n;

    select string_agg(i.id::text, ', ')
      into rows_mismatch
      from blood_inventory i
     where i.units_available > 0
       and not exists (
           select 1 from blood_inventory_batches b
            where b.id = i.id
              and b.blood_bank_id = i.blood_bank_id
              and b.blood_type = i.blood_type
              and b.units_received = i.units_available
              and b.units_remaining = i.units_available
              and b.expiry_date = i.expiry_date
              and b.source = 'MIGRATED');

    if totals_mismatch is not null or rows_mismatch is not null then
        raise exception 'V19 aborted, nothing was changed: the converted batches do not match blood_inventory. Totals: %. Rows without a matching batch: %',
            coalesce(totals_mismatch, 'all match'), coalesce(rows_mismatch, 'none');
    end if;
end
$$;

-- ------------------------------------------------------------------
-- 4. The migration output: what happened to each row
-- ------------------------------------------------------------------

do $$
declare
    r record;
    converted bigint;
    skipped   bigint;
begin
    for r in
        select b.name, i.blood_type, i.id, i.expiry_date
          from blood_inventory i
          join blood_banks b on b.id = i.blood_bank_id
         where i.units_available = 0
         order by b.name, i.blood_type
    loop
        raise notice 'V19: skipped % % (row %): 0 units, so no batch was made (its expiry date % described no stock)',
            r.name, r.blood_type, r.id, r.expiry_date;
    end loop;

    for r in
        select b.name, x.blood_type, x.units_remaining, x.expiry_date
          from blood_inventory_batches x
          join blood_banks b on b.id = x.blood_bank_id
         where x.source = 'MIGRATED' and x.expiry_noticed_at is not null
         order by b.name, x.blood_type
    loop
        raise notice 'V19: % % converted to an ALREADY EXPIRED batch: % unit(s), expired %; no longer counted as usable stock',
            r.name, r.blood_type, r.units_remaining, r.expiry_date;
    end loop;

    select count(*) into converted from blood_inventory_batches where source = 'MIGRATED';
    select count(*) into skipped from blood_inventory where units_available = 0;
    raise notice 'V19: converted % row(s) into MIGRATED batches; skipped % row(s) with 0 units', converted, skipped;
end
$$;

-- ------------------------------------------------------------------
-- 5. Retire the legacy table (kept until V20, read by nothing)
-- ------------------------------------------------------------------

alter table blood_inventory rename to blood_inventory_pre_batches;

-- Its index-backed names (the primary key, the unique constraint and the plain
-- index) are unique across the whole schema, so they are renamed too: nothing new
-- may ever collide with them. Every other constraint is renamed as well, for
-- tidiness. Found by looking them up rather than listed by hand, because
-- PostgreSQL 18 also names NOT NULL constraints and PostgreSQL 16 (the Docker
-- demo's image) does not: a hand-written list would fail on one of the two.
alter index idx_blood_inventory_bank rename to idx_blood_inventory_pre_batches_bank;
alter table blood_inventory_pre_batches
    rename constraint uq_blood_inventory_bank_type to uq_blood_inventory_pre_batches_bank_type;

do $$
declare
    c record;
begin
    for c in
        select conname
          from pg_constraint
         where conrelid = 'blood_inventory_pre_batches'::regclass
           and left(conname, 16) = 'blood_inventory_'
    loop
        execute format('alter table blood_inventory_pre_batches rename constraint %I to %I',
                       c.conname, 'blood_inventory_pre_batches_' || substr(c.conname, 17));
    end loop;
end
$$;

comment on table blood_inventory_pre_batches is
    'Legacy one-row-per-(bank, group) stock, converted into blood_inventory_batches by V19. '
    'Read and written by nothing. Kept only as a record of what was converted; dropped by V20.';
