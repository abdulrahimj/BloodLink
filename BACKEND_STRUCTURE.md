# Backend Structure — Quick Reference

A map of the backend's folders, for orientation rather than exhaustive detail. Every
folder listed here is a Java package under `backend/src/main/java/com/bloodlink/`.
Most follow the same shape — a `Controller` (the API endpoint), a `Service` (the
logic and rules), and a `dto/` subfolder (the shapes of data sent and received) —
so once one is familiar the rest read the same way.

| Package | Responsible for |
|---|---|
| `admin` | The Administrator's own screens: listing every user account, activating/suspending one, and the two citywide reports (donation trends, request outcomes). |
| `appeal` | A blood bank asking donors of one blood group to come in and donate — the "Send Appeal" feature. |
| `auth` | Signing up (donors only) and signing in — issuing the login token every other request depends on. |
| `bloodbank` | Blood bank reference data (name, location) and the shared rule for "which blood bank is this staff member acting on behalf of." |
| `booking` | Donors booking a donation visit (a blood bank, a date, morning or afternoon), cancelling it, and the bank's list of upcoming visits; never touches stock. |
| `config` | Application-wide setup: security rules (who can reach which endpoint) and the real-time (WebSocket) connection. |
| `domain` | The core data shapes — User, Donor, BloodBank, Hospital, Donation, BloodRequest, BloodInventoryBatch, Notification — matching the database tables directly. Its `enums/` subfolder holds the fixed-choice values used throughout (blood type, urgency level, request status, account status, and the like). |
| `donation` | Recording an actual donation event: the blood drawn, the units added to stock, the donor's eligibility clock reset, and their profile blood group taken from the test. |
| `donor` | A donor's own profile and donation history, plus the staff-facing lookup used to find a donor by phone before recording their donation, the bank's donor roster, and correcting a donor's blood group after a test. |
| `health` | A single "is the server up" check, used for monitoring. |
| `hospital` | Hospital reference data (name, location) — the hospital equivalent of `bloodbank`. |
| `inventory` | A blood bank's stock levels — viewing them, and adding a new batch of units. |
| `notification` | Creating and delivering the four kinds of alerts (low stock, donation appeal, new request, request status change) — both the stored record and the live push to a connected screen. |
| `repository` | The layer that actually talks to the database — one file per data shape in `domain`, used by every service above it. |
| `request` | The hospital-to-blood-bank request lifecycle: submitting a request, and a blood bank approving, rejecting, or fulfilling it. |
| `security` | How a signed-in user is recognised on every request — reading and checking the login token, and enforcing that a suspended account is blocked immediately. |
| `web` | Shared plumbing for turning errors into a consistent, readable response shape across the whole API. |
