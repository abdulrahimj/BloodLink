-- Business Rule 34: when failed sign-ins lock a phone number, one entry goes in the audit log.
-- The counters live in server memory, so this is the only schema change. No existing row is changed.
--
-- Nobody signed in caused a lock, and the number may belong to no account, so this one action has no actor
-- (the system did it) and an optional subject. Existing rows are only read to validate the constraints below.

alter table audit_log
    alter column actor_user_id drop not null;

-- Dropping NOT NULL must not let any other entry lose its actor: only SIGN_IN_LOCKED has none, and it never has one.
alter table audit_log
    add constraint audit_log_actor_absent_only_for_sign_in_lock
        check ((actor_user_id is null) = (action = 'SIGN_IN_LOCKED'));

alter table audit_log
    drop constraint audit_log_action_check;
alter table audit_log
    add constraint audit_log_action_check
        check (action in ('ACCOUNT_CREATED', 'ACCOUNT_EDITED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED',
                          'BLOOD_BANK_CREATED', 'HOSPITAL_CREATED', 'STOCK_WRITTEN_OFF',
                          'DONOR_BLOOD_GROUP_UPDATED', 'ADMIN_PASSWORD_RESET', 'PASSWORD_REQUEST_CLOSED',
                          'SIGN_IN_LOCKED'));

-- A lock's subject is the account holding the number, or nobody for an unregistered one (the number is in details).
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
     or (action = 'SIGN_IN_LOCKED'
            and target_blood_bank_id is null and target_hospital_id is null and target_batch_id is null)
    );
