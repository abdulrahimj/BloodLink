-- Business Rule 28: a change to a donor's profile blood group (from a donation's test, or a manual
-- correction by blood bank staff) is audited as DONOR_BLOOD_GROUP_UPDATED, against the donor's account.
--
-- Constraints only: no column, no data change. Existing entries are read here (to validate the new
-- constraints), never changed, so V12's append-only triggers are not involved, exactly as in V14 and V18.

alter table audit_log
    drop constraint audit_log_action_check;
alter table audit_log
    add constraint audit_log_action_check
        check (action in ('ACCOUNT_CREATED', 'ACCOUNT_EDITED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED',
                          'BLOOD_BANK_CREATED', 'HOSPITAL_CREATED', 'STOCK_WRITTEN_OFF',
                          'DONOR_BLOOD_GROUP_UPDATED'));

-- Still exactly one target per entry, and the one its action is about. The new action's subject is
-- the donor's user account, like the four ACCOUNT_ actions; its actor is blood bank staff.
alter table audit_log
    drop constraint audit_log_target_matches_action;
alter table audit_log
    add constraint audit_log_target_matches_action check (
        (action in ('ACCOUNT_CREATED', 'ACCOUNT_EDITED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED',
                    'DONOR_BLOOD_GROUP_UPDATED')
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
