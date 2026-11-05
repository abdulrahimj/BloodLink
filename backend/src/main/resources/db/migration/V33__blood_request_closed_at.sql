-- Business Rule 36: when a blood request finished (FULFILLED, REJECTED or CANCELLED), for the Request history page.
-- Not derivable for every row: REJECTED requests from before V9 have no decline rows, and V15 dated converted
-- fulfilments at created_at. So it is stored from now on, and filled in below from the best evidence there is.

alter table blood_requests
    add column closed_at timestamp with time zone;

-- CANCELLED: the moment the hospital cancelled it (Business Rule 35).
update blood_requests
   set closed_at = cancelled_at
 where status = 'CANCELLED';

-- FULFILLED: the last contribution, the one that covered it; created_at if none was ever recorded.
update blood_requests r
   set closed_at = coalesce(
           (select max(c.contributed_at) from blood_request_contributions c where c.blood_request_id = r.id),
           r.created_at)
 where r.status = 'FULFILLED';

-- REJECTED: the last bank's decline; created_at for one rejected before declines were recorded (V9).
update blood_requests r
   set closed_at = coalesce(
           (select max(d.declined_at) from blood_request_declines d where d.blood_request_id = r.id),
           r.created_at)
 where r.status = 'REJECTED';

-- An open request has no closing time. (The reverse is set by the application on every close; it is not a CHECK
-- here because the demo seed inserts finished requests after this file runs, and V907 fills theirs in.)
alter table blood_requests
    add constraint blood_requests_closed_at_only_when_finished
        check (closed_at is null or status in ('FULFILLED', 'REJECTED', 'CANCELLED'));

-- A cancelled request closed exactly when it was cancelled.
alter table blood_requests
    add constraint blood_requests_cancelled_closed_at_matches
        check (status <> 'CANCELLED' or closed_at = cancelled_at);

-- The history page reads a hospital's finished requests newest closed first.
create index idx_blood_requests_hospital_closed
    on blood_requests (hospital_id, closed_at desc, id desc)
    where closed_at is not null;
