-- Business Rule 31: an administrator resets another account's password. The audit log records it as
-- ADMIN_PASSWORD_RESET (subject: that account) and the account holder is told by PASSWORD_RESET_BY_ADMIN.
--
-- Constraints only: no column, no data change. Existing rows are read here (to validate the new constraints),
-- never changed, so V12's append-only triggers are not involved, exactly as in V14, V18 and V26.

alter table audit_log
    drop constraint audit_log_action_check;
alter table audit_log
    add constraint audit_log_action_check
        check (action in ('ACCOUNT_CREATED', 'ACCOUNT_EDITED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED',
                          'BLOOD_BANK_CREATED', 'HOSPITAL_CREATED', 'STOCK_WRITTEN_OFF',
                          'DONOR_BLOOD_GROUP_UPDATED', 'ADMIN_PASSWORD_RESET'));

-- Still exactly one target per entry; the new action's subject is a user account, like the ACCOUNT_ actions.
alter table audit_log
    drop constraint audit_log_target_matches_action;
alter table audit_log
    add constraint audit_log_target_matches_action check (
        (action in ('ACCOUNT_CREATED', 'ACCOUNT_EDITED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED',
                    'DONOR_BLOOD_GROUP_UPDATED', 'ADMIN_PASSWORD_RESET')
            and target_user_id is not null and target_blood_bank_id is null
            and target_hospital_id is null and target_batch_id is null)
     or (action = 'BLOOD_BANK_CREATED'
            and target_blood_bank_id is not null and target_user_id is null
            and target_hospital_id is null and target_batch_id is null)
     or (action = 'HOSPITAL_CREATED'
            and target_hospital_id is not null and target_user_id is null
            and target_blood_bank_id is null and target_batch_id is null)
     or (action = 'STOCK_WRITTEN_OFF'
            and target_batch_id is not null and target_user_id is null
            and target_blood_bank_id is null and target_hospital_id is null)
    );

alter table notifications
    drop constraint notifications_type_check;
alter table notifications
    add constraint notifications_type_check
        check (type in ('LOW_STOCK_ALERT', 'DONATION_APPEAL', 'DONATION_APPEAL_ACCEPTED',
                        'NEW_REQUEST', 'REQUEST_STATUS_CHANGE', 'REQUEST_PROGRESS',
                        'BATCH_EXPIRY_REMINDER', 'BATCH_EXPIRED',
                        'DONATION_BOOKED', 'DONATION_BOOKING_CANCELLED', 'DONATION_BOOKING_REMINDER',
                        'PASSWORD_RESET_BY_ADMIN'));
