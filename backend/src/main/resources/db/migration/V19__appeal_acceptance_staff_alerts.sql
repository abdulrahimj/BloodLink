-- Keep the origin and requested blood group on donor-facing appeal notifications
-- so an acceptance can be routed to the bank that sent the appeal.
alter table notifications
    add column source_blood_bank_id uuid references blood_banks (id),
    add column blood_type varchar(3);

-- Backfill existing appeal notifications from the exact message template used
-- by NotificationService, preserving their origin without exposing message text
-- as an ongoing source of truth.
update notifications n
set source_blood_bank_id = b.id,
    blood_type = trim(substring(n.message from char_length(b.name) + 8 for 3))
from blood_banks b
where n.type = 'DONATION_APPEAL'
  and n.message like b.name || ' needs % donors. Please donate if you are able.%';

alter table notifications
    drop constraint notifications_type_check;
alter table notifications
    add constraint notifications_type_check
        check (type in ('LOW_STOCK_ALERT', 'DONATION_APPEAL', 'DONATION_APPEAL_ACCEPTED',
                        'NEW_REQUEST', 'REQUEST_STATUS_CHANGE', 'REQUEST_PROGRESS'));

create index idx_notifications_appeal_bank_type
    on notifications (source_blood_bank_id, blood_type, created_at)
    where type = 'DONATION_APPEAL';
