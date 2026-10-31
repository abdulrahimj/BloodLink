-- Business Rule 32: a sign-in (token) issued before the account's password last changed stops working.
-- Set whenever a password is set or changed; every authenticated request and WebSocket connection compares it
-- with the token's issued-at time, at whole-second precision.
--
-- No data change: existing rows stay NULL, meaning "no restriction", so their tokens work until they expire.
alter table users
    add column password_changed_at timestamp with time zone;
