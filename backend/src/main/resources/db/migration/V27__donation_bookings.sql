-- Business Rule 29: an eligible donor books a donation visit at a blood bank, for a date and a
-- morning or afternoon. A booking never changes stock. Plus three notification types for it.

create table donation_bookings (
    id                uuid primary key default gen_random_uuid(),
    donor_id          uuid not null references donors (id),
    blood_bank_id     uuid not null references blood_banks (id),
    booking_date      date not null,
    time_of_day       varchar(16) not null
        check (time_of_day in ('MORNING', 'AFTERNOON')),
    status            varchar(16) not null default 'BOOKED'
        check (status in ('BOOKED', 'CANCELLED', 'ATTENDED', 'MISSED')),
    -- The donation that attended it; set exactly when ATTENDED.
    donation_id       uuid unique references donations (id),
    created_at        timestamp with time zone not null default now(),
    -- When it left BOOKED; null exactly while BOOKED.
    closed_at         timestamp with time zone,
    -- When the day-before reminder went to the donor, so it is sent once.
    reminder_sent_at  timestamp with time zone,

    constraint donation_bookings_attended_has_donation check ((status = 'ATTENDED') = (donation_id is not null)),
    constraint donation_bookings_closed_when_not_booked check ((status = 'BOOKED') = (closed_at is null))
);

-- At most one open booking per donor.
create unique index uq_donation_bookings_one_open_per_donor
    on donation_bookings (donor_id) where status = 'BOOKED';

-- A bank's upcoming visits (the staff list) and the daily job's date scans.
create index idx_donation_bookings_open_by_bank_date
    on donation_bookings (blood_bank_id, booking_date) where status = 'BOOKED';

-- Three notification types: DONATION_BOOKED and DONATION_BOOKING_CANCELLED to the bank's
-- active staff, DONATION_BOOKING_REMINDER to the donor the day before.
alter table notifications
    drop constraint notifications_type_check;
alter table notifications
    add constraint notifications_type_check
        check (type in ('LOW_STOCK_ALERT', 'DONATION_APPEAL', 'DONATION_APPEAL_ACCEPTED',
                        'NEW_REQUEST', 'REQUEST_STATUS_CHANGE', 'REQUEST_PROGRESS',
                        'BATCH_EXPIRY_REMINDER', 'BATCH_EXPIRED',
                        'DONATION_BOOKED', 'DONATION_BOOKING_CANCELLED', 'DONATION_BOOKING_REMINDER'));
