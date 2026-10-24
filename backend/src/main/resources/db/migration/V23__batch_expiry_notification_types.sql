-- Two notification types for the daily batch expiry notices (Business Rule 26):
-- BATCH_EXPIRY_REMINDER, batches 7 days from expiry (informational), and
-- BATCH_EXPIRED, expired batches still holding units (please write them off).
-- Only the list of allowed values changes: no table, column or row is touched.
-- (The drop of blood_inventory_pre_batches, once planned as V23, is now V24.)
alter table notifications
    drop constraint notifications_type_check;
alter table notifications
    add constraint notifications_type_check
        check (type in ('LOW_STOCK_ALERT', 'DONATION_APPEAL', 'DONATION_APPEAL_ACCEPTED',
                        'NEW_REQUEST', 'REQUEST_STATUS_CHANGE', 'REQUEST_PROGRESS',
                        'BATCH_EXPIRY_REMINDER', 'BATCH_EXPIRED'));
