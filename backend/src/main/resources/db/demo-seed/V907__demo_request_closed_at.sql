-- Business Rule 36: V904's finished demo requests are inserted after V33 ran, so they have no closed_at.
-- Filled in with V33's own rules; only rows still missing one are touched.

update blood_requests r
   set closed_at = coalesce(
           (select max(c.contributed_at) from blood_request_contributions c where c.blood_request_id = r.id),
           r.created_at)
 where r.status = 'FULFILLED' and r.closed_at is null;

-- V904's rejected requests carry no decline rows, so they close when they were raised.
update blood_requests r
   set closed_at = coalesce(
           (select max(d.declined_at) from blood_request_declines d where d.blood_request_id = r.id),
           r.created_at)
 where r.status = 'REJECTED' and r.closed_at is null;

update blood_requests
   set closed_at = cancelled_at
 where status = 'CANCELLED' and closed_at is null;
