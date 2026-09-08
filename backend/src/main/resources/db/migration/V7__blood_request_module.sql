-- Schema additions required by the blood request module (Figure 3.5, the
-- hospital-request-to-blood-bank sequence). Two columns, no new tables.

-- 1. Links a HOSPITAL_STAFF account to the one hospital they work at.
--
--    The exact counterpart of users.blood_bank_id from V5, and added for the same
--    reason: GET /api/requests must show a hospital only its own requests, and
--    POST /api/requests must stamp the caller's hospital onto the new row rather
--    than trusting a hospital id sent by the browser. Nothing else in the CLAUDE.md
--    schema connects a User to a Hospital — Donor is the only role with a link, and
--    hospitals has no notion of its staff.
--
--    Nullable: DONOR, BLOOD_BANK_STAFF and ADMIN users never have a hospital, and
--    ADMIN's access to requests is by role, not by this column (see RequestService).
--    Left unpopulated until an administrator user-management phase exists to assign
--    it; RequestService treats "hospital staff with no hospital" as having access to
--    nothing rather than failing to start up, matching how V5's column behaves.
alter table users
    add column hospital_id uuid references hospitals (id);

create index idx_users_hospital on users (hospital_id);

-- 2. The optional ward / patient reference carried on a request.
--
--    The Hospital Portal's request form has always had this field ("e.g. Maternity
--    Ward — Bed 12"); without a column it would be typed and silently discarded.
--    It is free text a clerk uses to match delivered units back to the patient who
--    needs them, so it is deliberately not modelled as an entity — there is no
--    patient record in this system, by design, and there should not be one.
--
--    Nullable: the field is optional on the form, and every request created before
--    this migration has none.
alter table blood_requests
    add column patient_reference varchar(255);
