-- Business Rule 35: the hospital that raised a blood request can cancel it while it is still open.
-- Units already supplied stay supplied, so no stock or contribution row is touched. No existing row is changed.

alter table blood_requests
    add column cancellation_reason varchar(32),
    add column cancellation_note   varchar(200),
    add column cancelled_by        uuid references users (id),
    add column cancelled_at        timestamp with time zone;

alter table blood_requests
    drop constraint blood_requests_status_check;
alter table blood_requests
    add constraint blood_requests_status_check
        check (status in ('PENDING', 'PARTIALLY_FULFILLED', 'FULFILLED', 'REJECTED', 'CANCELLED'));

alter table blood_requests
    add constraint blood_requests_cancellation_reason_check
        check (cancellation_reason in ('NO_LONGER_NEEDED', 'PATIENT_TRANSFERRED', 'OBTAINED_ELSEWHERE',
                                       'ENTERED_BY_MISTAKE', 'OTHER'));

-- Reason, who and when are all present exactly on a cancelled request; every other request has none of them.
alter table blood_requests
    add constraint blood_requests_cancellation_matches_status check (
        (status = 'CANCELLED' and cancellation_reason is not null
            and cancelled_by is not null and cancelled_at is not null)
     or (status <> 'CANCELLED' and cancellation_reason is null and cancellation_note is null
            and cancelled_by is null and cancelled_at is null)
    );

-- A note is never blank (no note is stored as NULL), and OTHER must be explained.
alter table blood_requests
    add constraint blood_requests_cancellation_note_not_blank
        check (cancellation_note is null or btrim(cancellation_note) <> '');
alter table blood_requests
    add constraint blood_requests_cancellation_note_required_for_other
        check (cancellation_reason is distinct from 'OTHER' or cancellation_note is not null);

alter table notifications
    drop constraint notifications_type_check;
alter table notifications
    add constraint notifications_type_check
        check (type in ('LOW_STOCK_ALERT', 'DONATION_APPEAL', 'DONATION_APPEAL_ACCEPTED',
                        'NEW_REQUEST', 'REQUEST_STATUS_CHANGE', 'REQUEST_PROGRESS',
                        'BATCH_EXPIRY_REMINDER', 'BATCH_EXPIRED',
                        'DONATION_BOOKED', 'DONATION_BOOKING_CANCELLED', 'DONATION_BOOKING_REMINDER',
                        'PASSWORD_RESET_BY_ADMIN', 'PASSWORD_RESET_REQUESTED', 'REQUEST_CANCELLED'));

-- The audit entry's subject is the request itself, the same pattern as target_batch_id (V18).
-- NO ACTION, like every audit foreign key: a request in the log can never be deleted.
alter table audit_log
    add column target_blood_request_id uuid references blood_requests (id);

alter table audit_log
    drop constraint audit_log_action_check;
alter table audit_log
    add constraint audit_log_action_check
        check (action in ('ACCOUNT_CREATED', 'ACCOUNT_EDITED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED',
                          'BLOOD_BANK_CREATED', 'HOSPITAL_CREATED', 'STOCK_WRITTEN_OFF',
                          'DONOR_BLOOD_GROUP_UPDATED', 'ADMIN_PASSWORD_RESET', 'PASSWORD_REQUEST_CLOSED',
                          'SIGN_IN_LOCKED', 'REQUEST_CANCELLED'));

-- Existing rows are only read to validate this, so V12's append-only triggers are not involved.
alter table audit_log
    drop constraint audit_log_target_matches_action;
alter table audit_log
    add constraint audit_log_target_matches_action check (
        (action in ('ACCOUNT_CREATED', 'ACCOUNT_EDITED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED',
                    'DONOR_BLOOD_GROUP_UPDATED', 'ADMIN_PASSWORD_RESET', 'PASSWORD_REQUEST_CLOSED')
            and target_user_id is not null and target_blood_bank_id is null
            and target_hospital_id is null and target_batch_id is null and target_blood_request_id is null)
     or (action = 'BLOOD_BANK_CREATED'
            and target_blood_bank_id is not null and target_user_id is null
            and target_hospital_id is null and target_batch_id is null and target_blood_request_id is null)
     or (action = 'HOSPITAL_CREATED'
            and target_hospital_id is not null and target_user_id is null
            and target_blood_bank_id is null and target_batch_id is null and target_blood_request_id is null)
     or (action = 'STOCK_WRITTEN_OFF'
            and target_batch_id is not null and target_user_id is null
            and target_blood_bank_id is null and target_hospital_id is null and target_blood_request_id is null)
     or (action = 'SIGN_IN_LOCKED'
            and target_blood_bank_id is null and target_hospital_id is null and target_batch_id is null
            and target_blood_request_id is null)
     or (action = 'REQUEST_CANCELLED'
            and target_blood_request_id is not null and target_user_id is null
            and target_blood_bank_id is null and target_hospital_id is null and target_batch_id is null)
    );
