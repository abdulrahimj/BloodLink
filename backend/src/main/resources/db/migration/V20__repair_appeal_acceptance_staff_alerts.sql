-- Track whether the durable staff alert was created for an acceptance. This also
-- lets a retried donor request repair an older acceptance that predates alerts.
alter table notifications
    add column acceptance_alert_sent boolean not null default false;

-- Deliver alerts for acceptances that were recorded before this flow was deployed.
-- The accepted appeal row already retains its donor, originating bank and group.
insert into notifications (recipient_user_id, message, type, is_read, accepted, created_at,
                           source_blood_bank_id, blood_type, acceptance_alert_sent)
select staff.id,
       donor.full_name || ' accepted your appeal for ' || appeal.blood_type || '.',
       'DONATION_APPEAL_ACCEPTED', false, false, now(),
       appeal.source_blood_bank_id, appeal.blood_type, false
from notifications appeal
join users donor on donor.id = appeal.recipient_user_id
join users staff on staff.blood_bank_id = appeal.source_blood_bank_id
                and staff.role = 'BLOOD_BANK_STAFF'
where appeal.type = 'DONATION_APPEAL'
  and appeal.accepted = true
  and appeal.source_blood_bank_id is not null
  and appeal.blood_type is not null
  and not exists (
      select 1
      from notifications sent
      where sent.recipient_user_id = staff.id
        and sent.type = 'DONATION_APPEAL_ACCEPTED'
        and sent.message = donor.full_name || ' accepted your appeal for ' || appeal.blood_type || '.'
  );

-- Mark repaired and already-alerted acceptances complete, while leaving rows
-- without a currently assigned staff recipient retryable.
update notifications appeal
set acceptance_alert_sent = true
where appeal.type = 'DONATION_APPEAL'
  and appeal.accepted = true
  and appeal.source_blood_bank_id is not null
  and appeal.blood_type is not null
  and exists (
      select 1
      from users staff
      where staff.blood_bank_id = appeal.source_blood_bank_id
        and staff.role = 'BLOOD_BANK_STAFF'
  );
