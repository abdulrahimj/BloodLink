-- A handful of each of the four Notification types, addressed to real
-- accounts this file's earlier siblings created, so the bell badge and the
-- full Notifications page are never empty on a first demo login. Wording
-- matches NotificationService's own message templates exactly, so these read
-- as genuine history rather than obviously-different placeholder text.
--
-- Most are left unread (is_read = false) so the badge shows a number
-- immediately; a couple are marked read to give the list some history rather
-- than every row looking freshly arrived.

-- ---------- LOW_STOCK_ALERT — addressed to the affected bank's own staff ----------
insert into notifications (recipient_user_id, message, type, is_read, created_at) values
    ('a0000000-0000-0000-0000-000000000004',
     'B- stock at Makeni Regional Blood Bank has fallen to 10 units — now Critical. Consider appealing to donors straight away.',
     'LOW_STOCK_ALERT', false, now() - interval '2 days'),
    ('a0000000-0000-0000-0000-000000000004',
     'AB- stock at Makeni Regional Blood Bank has fallen to 10 units — now Critical. Consider appealing to donors straight away.',
     'LOW_STOCK_ALERT', false, now() - interval '3 days'),
    ('a0000000-0000-0000-0000-000000000005',
     'AB- stock at Holy Spirit Blood Bank has fallen to 35 units — now Low. Consider appealing to donors.',
     'LOW_STOCK_ALERT', true, now() - interval '6 days');

-- ---------- DONATION_APPEAL — addressed to donors whose blood type matches ----------
insert into notifications (recipient_user_id, message, type, is_read, created_at)
select u.id,
       'Makeni Regional Blood Bank needs B- donors. Please donate if you are able. Donation drive at the lab this Saturday, 9am to 4pm.',
       'DONATION_APPEAL', false, now() - interval '1 day'
from users u where u.email = 'donor0816@blooddemo.example.com'; -- first donor in the B- range

insert into notifications (recipient_user_id, message, type, is_read, created_at)
select u.id,
       'Makeni Regional Blood Bank needs AB- donors. Please donate if you are able.',
       'DONATION_APPEAL', false, now() - interval '2 days'
from users u where u.email = 'donor0841@blooddemo.example.com'; -- first donor in the AB- range

insert into notifications (recipient_user_id, message, type, is_read, created_at)
select u.id,
       'Holy Spirit Blood Bank needs AB- donors. Please donate if you are able. Every donor helps.',
       'DONATION_APPEAL', true, now() - interval '5 days'
from users u where u.email = 'donor0850@blooddemo.example.com'; -- last donor, also AB-

-- ---------- NEW_REQUEST — addressed to staff at banks that could fulfil it ----------
insert into notifications (recipient_user_id, message, type, is_read, created_at) values
    ('a0000000-0000-0000-0000-000000000004',
     'Makeni Regional Hospital has requested 2 units of B- (Emergency).',
     'NEW_REQUEST', false, now() - interval '1 days'),
    ('a0000000-0000-0000-0000-000000000004',
     'Holy Spirit Hospital has requested 3 units of O+ (Urgent). For Emergency Ward — Bed 4.',
     'NEW_REQUEST', false, now() - interval '4 days'),
    ('a0000000-0000-0000-0000-000000000005',
     'Makeni Regional Hospital has requested 2 units of AB+ (Routine).',
     'NEW_REQUEST', true, now() - interval '8 days');

-- ---------- REQUEST_STATUS_CHANGE — addressed to the raising hospital's own staff ----------
insert into notifications (recipient_user_id, message, type, is_read, created_at) values
    ('a0000000-0000-0000-0000-000000000002',
     'Your request for 3 units of A+ was approved by Holy Spirit Blood Bank. The units will be issued on fulfilment.',
     'REQUEST_STATUS_CHANGE', false, now() - interval '3 days'),
    ('a0000000-0000-0000-0000-000000000002',
     'Your request for 4 units of O+ has been fulfilled by Makeni Regional Blood Bank. The units have been issued.',
     'REQUEST_STATUS_CHANGE', false, now() - interval '10 days'),
    ('a0000000-0000-0000-0000-000000000003',
     'Your request for 8 units of B- was rejected. A rejection closes the request for every blood bank, so please submit a new request if the blood is still needed.',
     'REQUEST_STATUS_CHANGE', true, now() - interval '15 days');
