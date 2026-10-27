-- Business Rule 27: an account whose password was set by someone else (an administrator,
-- or the startup settings that create the first administrator) must choose its own
-- password before it can do anything else.
--
-- Every existing account stays false: their passwords are already in use, and this rule
-- applies only to accounts created after it. Self-registered donors chose their own
-- password, so they are never flagged.
alter table users
    add column must_change_password boolean not null default false;
