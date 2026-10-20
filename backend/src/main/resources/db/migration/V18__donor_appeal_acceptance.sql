-- A donor's response is recorded on the appeal notification addressed to them.
alter table notifications
    add column accepted boolean not null default false;
