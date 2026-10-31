-- Business Rule 33: someone who forgot their password asks for help from the Sign in page. Active administrators are
-- alerted, call back the registered number and reset the password themselves (Business Rule 31). Nothing is reset here.
--
-- New table only, plus one notification type and one audit action. No existing row is changed.

create table password_reset_requests (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid not null references users (id),
    -- The latest time the account holder asked; moved forward when they ask again an hour or more later.
    requested_at timestamp with time zone not null default now(),
    status       varchar(16) not null default 'WAITING'
        constraint password_reset_requests_status_check check (status in ('WAITING', 'DONE')),
    resolution   varchar(16)
        constraint password_reset_requests_resolution_check check (resolution in ('RESET', 'CLOSED')),
    handled_by   uuid references users (id),
    handled_at   timestamp with time zone,
    note         varchar(200)
        constraint password_reset_requests_note_not_blank check (note is null or btrim(note) <> ''),
    -- Waiting means nobody has handled it yet; done means an administrator did, one way or the other, at a known time.
    constraint password_reset_requests_waiting_is_unhandled check (
        (status = 'WAITING' and resolution is null and handled_by is null and handled_at is null and note is null)
     or (status = 'DONE' and resolution is not null and handled_by is not null and handled_at is not null)
    ),
    -- Only a request closed without a reset carries a note.
    constraint password_reset_requests_note_only_when_closed check (note is null or resolution = 'CLOSED')
);

-- At most one waiting request per account: a second ask updates the first instead.
create unique index uq_password_reset_requests_one_waiting_per_user
    on password_reset_requests (user_id) where status = 'WAITING';

-- The administrators' list, newest first within each status.
create index idx_password_reset_requests_status_requested
    on password_reset_requests (status, requested_at desc);

alter table notifications
    drop constraint notifications_type_check;
alter table notifications
    add constraint notifications_type_check
        check (type in ('LOW_STOCK_ALERT', 'DONATION_APPEAL', 'DONATION_APPEAL_ACCEPTED',
                        'NEW_REQUEST', 'REQUEST_STATUS_CHANGE', 'REQUEST_PROGRESS',
                        'BATCH_EXPIRY_REMINDER', 'BATCH_EXPIRED',
                        'DONATION_BOOKED', 'DONATION_BOOKING_CANCELLED', 'DONATION_BOOKING_REMINDER',
                        'PASSWORD_RESET_BY_ADMIN', 'PASSWORD_RESET_REQUESTED'));

-- Closing a request without a reset is audited; its subject is the account that asked, like the ACCOUNT_ actions.
-- Existing rows are only read to validate the new constraints, so V12's append-only triggers are not involved.
alter table audit_log
    drop constraint audit_log_action_check;
alter table audit_log
    add constraint audit_log_action_check
        check (action in ('ACCOUNT_CREATED', 'ACCOUNT_EDITED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED',
                          'BLOOD_BANK_CREATED', 'HOSPITAL_CREATED', 'STOCK_WRITTEN_OFF',
                          'DONOR_BLOOD_GROUP_UPDATED', 'ADMIN_PASSWORD_RESET', 'PASSWORD_REQUEST_CLOSED'));

alter table audit_log
    drop constraint audit_log_target_matches_action;
alter table audit_log
    add constraint audit_log_target_matches_action check (
        (action in ('ACCOUNT_CREATED', 'ACCOUNT_EDITED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED',
                    'DONOR_BLOOD_GROUP_UPDATED', 'ADMIN_PASSWORD_RESET', 'PASSWORD_REQUEST_CLOSED')
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
