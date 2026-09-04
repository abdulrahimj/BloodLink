-- BloodLink initial schema.
-- Flyway owns the schema; Hibernate is validate-only. Hand-written, not generated.
--
-- Conventions:
--   * UUID primary keys, DEFAULT gen_random_uuid() (PostgreSQL 13+ core function)
--     so rows inserted by hand still get an id; the application also supplies one.
--   * Enums are stored as text with CHECK constraints matching the Java enums.
--   * blood_type is stored as its clinical label ('A+', 'O-', ...) — see BloodTypeConverter.
--   * created_at / last_updated are "timestamp with time zone", UTC (maps to java.time.Instant).

create table users (
    id            uuid primary key default gen_random_uuid(),
    full_name     varchar(255) not null,
    email         varchar(255) not null unique,
    password_hash varchar(255) not null,
    role          varchar(32)  not null
        check (role in ('DONOR', 'HOSPITAL_STAFF', 'BLOOD_BANK_STAFF', 'ADMIN')),
    phone         varchar(32)  not null,
    created_at    timestamp with time zone not null default now()
);

create table blood_banks (
    id            uuid primary key default gen_random_uuid(),
    name          varchar(255) not null,
    location      varchar(255) not null,
    contact_phone varchar(32)  not null
);

create table hospitals (
    id            uuid primary key default gen_random_uuid(),
    name          varchar(255) not null,
    location      varchar(255) not null,
    contact_phone varchar(32)  not null
);

create table donors (
    id                 uuid primary key default gen_random_uuid(),
    user_id            uuid not null unique references users (id),
    blood_type         varchar(3) not null
        check (blood_type in ('A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-')),
    date_of_birth      date not null,
    gender             varchar(16) not null check (gender in ('MALE', 'FEMALE')),
    last_donation_date date,
    address            varchar(255) not null
);

create table blood_inventory (
    id              uuid primary key default gen_random_uuid(),
    blood_bank_id   uuid not null references blood_banks (id),
    blood_type      varchar(3) not null
        check (blood_type in ('A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-')),
    units_available integer not null default 0,
    expiry_date     date not null,
    last_updated    timestamp with time zone not null default now(),
    constraint uq_blood_inventory_bank_type unique (blood_bank_id, blood_type)
);

create table donations (
    id            uuid primary key default gen_random_uuid(),
    donor_id      uuid not null references donors (id),
    blood_bank_id uuid not null references blood_banks (id),
    donation_date date not null,
    units_donated integer not null,
    -- Verified by testing at donation time; not copied from donors.blood_type.
    blood_type    varchar(3) not null
        check (blood_type in ('A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-'))
);

create table blood_requests (
    id                         uuid primary key default gen_random_uuid(),
    hospital_id                uuid not null references hospitals (id),
    blood_type                 varchar(3) not null
        check (blood_type in ('A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-')),
    units_requested            integer not null,
    urgency_level              varchar(16) not null
        check (urgency_level in ('ROUTINE', 'URGENT', 'EMERGENCY')),
    status                     varchar(16) not null
        check (status in ('PENDING', 'APPROVED', 'FULFILLED', 'REJECTED')),
    fulfilled_by_blood_bank_id uuid references blood_banks (id),
    created_at                 timestamp with time zone not null default now()
);

create table notifications (
    id                uuid primary key default gen_random_uuid(),
    recipient_user_id uuid not null references users (id),
    message           text not null,
    type              varchar(32) not null
        check (type in ('LOW_STOCK_ALERT', 'DONATION_APPEAL', 'NEW_REQUEST', 'REQUEST_STATUS_CHANGE')),
    is_read           boolean not null default false,
    created_at        timestamp with time zone not null default now()
);

-- Foreign-key lookup indexes
create index idx_donors_user               on donors (user_id);
create index idx_blood_inventory_bank      on blood_inventory (blood_bank_id);
create index idx_donations_donor           on donations (donor_id);
create index idx_donations_blood_bank      on donations (blood_bank_id);
create index idx_blood_requests_hospital   on blood_requests (hospital_id);
create index idx_blood_requests_fulfiller  on blood_requests (fulfilled_by_blood_bank_id);
create index idx_blood_requests_status     on blood_requests (status);
create index idx_notifications_recipient   on notifications (recipient_user_id);
