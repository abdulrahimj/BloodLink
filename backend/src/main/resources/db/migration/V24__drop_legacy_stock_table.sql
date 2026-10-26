-- Stage 6 of the batch-stock redesign (Business Rule 24): V19 kept the old one-row-per-(bank, group) table as
-- blood_inventory_pre_batches, a safety copy nothing reads or writes. No CASCADE, so a dependency fails loudly.
drop table blood_inventory_pre_batches;
