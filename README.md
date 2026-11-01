# BloodLink

BloodLink is a web-based system for real-time blood bank logistics and voluntary donor management in Makeni City, Sierra Leone. It puts donors, hospitals, blood banks and a system administrator on one platform, so blood stock, donor eligibility and hospital requests are visible as they change instead of being tracked by hand at each facility.

It was built as a final-year BSc Computer Science dissertation project.

## Key features

- **Donor registration and eligibility tracking.** Donors register with their phone number (email is optional), see their own profile and donation history, and are shown whether they are currently eligible to give. Eligibility is a safety rule: someone still in the 90-day recovery period after a donation is never sent an appeal and cannot have a donation recorded.
- **Blood group from the blood bank's test.** A donor may register with a blood group or "I don't know yet". The group a blood bank tests and records is what counts: recording a donation saves the tested group to an empty profile, and replaces a different one only after the staff member confirms the change. Bank staff can also correct a donor's group after a test, with a note saying how it was tested. Every change goes into the audit log, and a donor starts receiving appeals for their group as soon as it is recorded.
- **Stock held batch by batch, each with its own expiry date.** Every new delivery ("Add New Batch") and every recorded donation becomes its own batch. Only usable blood counts as stock: once a batch passes its expiry date it is no longer counted, shown as available or issued anywhere. It is listed separately as "expired, awaiting write-off". Low and critical levels are judged on usable units only. Hospitals see live usable totals across all banks.
- **Earliest expiry first.** When a bank sends units to a hospital, they are taken from the batch that expires soonest, then the next. Each batch records exactly where its units went.
- **Writing off stock.** Bank staff can remove units from a batch that has expired, been damaged, failed testing or was entered by mistake. They give a reason, and each write-off goes into the permanent audit log.
- **Daily expiry reminders.** Every morning, each bank's staff are told which batches expire in 7 days. Each day after a batch expires, while it still holds units, they are reminded to write it off.
- **Booking a donation visit.** An eligible donor can book a visit at any blood bank: a day within the next 30 days, morning or afternoon. One visit at a time; they can cancel it up to the day itself. The bank's staff see upcoming visits on their dashboard and are told when one is booked or cancelled, and the donor gets a reminder the day before (or on the morning itself if they booked too late for that). When the donation is recorded the visit is marked attended; a visit nobody turned up for is marked missed the next morning. Suspending a donor's account cancels their visit and tells the bank. Booking never changes stock.
- **Hospital blood requests filled by several banks together.** A hospital raises a request; any blood bank with stock can contribute part or all of it, so two banks can cover a request neither could alone. Both sides see progress, such as how many units have arrived and from which banks. A hospital that no longer needs the blood can cancel an open request with a reason: units already supplied stay supplied, no stock returns, and the banks are told.
- **Donation appeals that donors can answer.** A blood bank running short can appeal to donors of the blood group it needs. Only eligible donors are asked, and a bank cannot repeat an appeal too often. A donor can accept an appeal from their portal, and the bank's staff are told straight away.
- **Real-time notifications.** Low-stock alerts, new requests, request progress and cancellations, appeals, appeal acceptances, expiry reminders and donation visit bookings and reminders are pushed to the right people as they happen. They are also stored, so nothing is lost if someone was offline. Notifications go to active accounts only: a suspended account receives none.
- **Admin console.** Administrators create and manage staff accounts, add blood banks and hospitals, read a permanent audit log of their own actions, of stock write-offs, of donor blood group changes and of request cancellations, and see reports with CSV downloads. Administrators have read-only oversight of stock: they can see every bank's stock, batches and batch history, but cannot add stock, record donations, contribute units to requests or write units off. Only a bank's own staff can change its stock.

Everyone signs in to one web application and sees the views for their role: Donor, Hospital Staff, Blood Bank Staff or Administrator.

## Tech stack

- **Frontend:** React 18, Vite, plain CSS (CSS custom properties for theming — no Tailwind, no UI library)
- **Backend:** Spring Boot 4.1.1, Java 25, Maven
- **Database:** PostgreSQL, schema managed by Flyway migrations (not `ddl-auto`)
- **Real-time:** Spring WebSocket with STOMP over SockJS
- **Auth:** Spring Security, stateless JWT

## Repository layout

| Folder | What is in it |
|---|---|
| `backend/` | The Spring Boot API and WebSocket server, database migrations, and backend tests |
| `backend/BloodLink.postman_collection.json` | Every API endpoint as a Postman request, set up for the demo accounts (import it into Postman) |
| `frontend/` | The React application |
| `frontend-mockups/` | The original design mockups the interface was built from |
| `docker-compose.yml` | One-command demo of the whole system |

## Running it locally for development

### You will need

- A JDK 25
- Node.js (a current LTS release) and npm
- PostgreSQL, running locally

### 1. Clone

```bash
git clone https://github.com/abdulrahimj/BloodLink.git
cd BloodLink
```

### 2. Create the database

```sql
CREATE DATABASE bloodlink_dev;
```

You do not need to create any tables: Flyway builds the schema the first time the backend starts.

### 3. Configure the backend

```bash
cd backend
cp src/main/resources/application.properties.example src/main/resources/application.properties
```

Open the new `application.properties` and replace `DB_USERNAME` and `DB_PASSWORD` with your own local PostgreSQL credentials. This file is git-ignored, so your credentials stay on your machine. (Alternatively, leave the file as copied and set `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` in the terminal you start the backend from.)

The login-token signing secret has a public placeholder default that is fine on your own machine (the backend prints a warning at startup to remind you). To use your own, which you should, see [Setting a real JWT secret](#setting-a-real-jwt-secret) below. The backend deliberately refuses to start on the placeholder under any profile other than the default and `demo`, and refuses a secret shorter than 32 bytes under every profile, so a real deployment cannot run on a weak secret by accident.

Every environment variable the backend reads, with its default, is listed in [`backend/.env.example`](backend/.env.example); the frontend's are in [`frontend/.env.example`](frontend/.env.example). Spring Boot does not read a `.env` file itself: set the variables in your terminal.

If you made your `application.properties` some time ago, compare it with the template: newer settings (such as the daily expiry reminder's `bloodlink.expiry-notifications.*`) are only in the template. They all have safe defaults, so an older copy still starts.

### 4. Start the backend

From the `backend/` folder:

```bash
./mvnw spring-boot:run        # macOS / Linux
.\mvnw.cmd spring-boot:run    # Windows (PowerShell)
```

It listens on <http://localhost:8080>. A quick check: <http://localhost:8080/api/health> should return `{"status":"ok"}`.

### 5. Start the frontend

In a second terminal, from the `frontend/` folder:

```bash
npm install
npm run dev
```

Open <http://localhost:5173>. The frontend expects the backend at `http://localhost:8080`; to point it elsewhere, copy `frontend/.env.example` to `frontend/.env.local` and change `VITE_API_BASE_URL`.

### A note on accounts

Only donors can register themselves. Hospital staff, blood bank staff and administrators are created by an administrator inside the app. A fresh, empty database has no administrator, so the first one is created from startup settings (next section). The quickest way to explore every role is the demo mode below, which comes with one account per role already in place.

Every account an administrator creates gets a **temporary password**, shown once to the administrator. At its first sign-in the account sees only a "Set your own password" screen, and the server refuses everything else until a new password is chosen. Donors who register themselves chose their own password, so they never see this screen.

### Resetting a forgotten password

**Asking for help.** On the Sign in page, **Forgot password?** opens a short form: type the phone number you sign in with and press **Send request**. The answer is always "If this number is registered, an administrator will contact you. Keep your phone nearby." — the same for every number, so nobody can use the form to find out who is registered — followed by "Or call BloodLink on …" when a support number is set (`BLOODLINK_SUPPORT_PHONE`, empty by default). BloodLink never resets a password from this form. For an active account, every active administrator gets a notification ("Password reset requested for +232… (name). Call back the registered number."); asking again within an hour adds nothing, and asking after an hour reminds them. Unknown and suspended numbers are recorded nowhere.

**Handling a request.** In the Admin Console, **Password requests** (with a red count while any are waiting, and a banner on User Accounts) lists who asked, their registered phone, role and when. **Call** dials the registered number: check you are speaking to the account holder, then press **Reset password**, which opens the Manage box at its Password section; once reset, the request is marked done automatically. If no reset is needed, **Mark done** closes it with an optional note (recorded in the Audit Log). The **Done** filter shows who handled each request, when, and how. A reset made from User Accounts › Manage closes a waiting request the same way. You cannot reset your own password here; another administrator must.

API: `POST /api/auth/password-reset-requests` `{ phone }` (public, always `202` with the same message; a malformed number is the same `400` as sign-in; `429` over the address limit below), `GET /api/auth/support-contact` (public), and for administrators `GET /api/admin/password-reset-requests?status=WAITING|DONE`, `GET /api/admin/password-reset-requests/count` and `POST /api/admin/password-reset-requests/{id}/close` `{ note }`.

**Resetting.** An administrator opens **User Accounts**, clicks **Manage** on the account (or its name), and under **Password** clicks **Reset password**, then confirms. BloodLink shows a new temporary password once, with a Copy button; give it to the person directly. Their old password stops working straight away, and at their next sign-in they must choose their own (the same "Set your own password" screen). The reset is recorded in the Audit Log and the person gets a notification; neither contains the password. An administrator cannot reset their own password this way (use Profile Settings), may reset another administrator's, and resetting a suspended account leaves it suspended. API: `POST /api/admin/users/{id}/password-reset`, administrators only, answering `{ account, temporaryPassword }` with `Cache-Control: no-store`.

### Limits on sign-in, "Forgot password?" and registration

Limits stop password guessing and flooding (Business Rule 34). Each number below is a setting; `backend/.env.example` lists them.

- **Sign-in, per phone number:** 3 failed attempts within 15 minutes lock that number for 15 minutes, for registered and unregistered numbers alike. While locked, every attempt is refused, even with the correct password. A successful sign-in resets the count, and an administrator's password reset clears a lock.
- **Sign-in, per internet address:** 30 failed attempts within 15 minutes, then wait 15 minutes.
- **"Forgot password?", per internet address:** 10 requests per hour.
- **Registration, per internet address:** 10 new accounts per hour.

Over a limit the API answers `429 Too Many Requests` with a `Retry-After` header (seconds) and a message such as "Too many attempts. Please wait 15 minutes and try again.", stating the real time left; the page counts down from the header ("Too many attempts. Please wait 14:32 before trying again."), or shows that message if the header is missing, and the form can be used again after the wait. On Sign in, a "Did an administrator reset your password? Try now." link under the banner drops the wait in that tab, since a reset clears the lock on the server; if the lock still stands, the next attempt restarts the countdown. The answer is the same whether or not a number is registered. Each phone lock adds one "Sign-in locked" entry to the Audit Log, made by the system. Counts are held in memory, so a restart forgets them. Behind a proxy, see DEPLOYMENT.md, "Rate limits".

### Signed out after a password change

Changing a password signs out every session made before it. After an administrator resets an account, anyone still signed in to that account (on any device) is sent back to Sign in with "Your password was changed. Please sign in again.", and its live notifications stop. When you change your own password (Profile Settings or the "Set your own password" screen), the device you used stays signed in and your other devices are signed out. Accounts whose password has not changed since this rule arrived (V29) are not affected. API: a refused request answers `401` with `"code": "PASSWORD_CHANGED"`, and `PATCH /api/users/me/password` now answers `200` with a fresh token in the sign-in shape (`{ token, tokenType, expiresIn, user }`) instead of `204`.

### The password rule

Every password that is **set or changed** must be 8 to 72 characters long and contain at least one capital letter (A–Z), one small letter (a–z), one number (0–9) and one special character (anything that is not a letter, a number or a space, e.g. `! @ # ?`). It applies to registration, Profile Settings' password change, the "Set your own password" screen, the first-administrator setting below and the hosted demo passwords ([DEPLOYMENT.md](DEPLOYMENT.md)); the temporary passwords the Admin Console generates always meet it. Each of those screens shows the five parts as a checklist that ticks as you type, and the button stays disabled until all five are met. The server checks again and its error names exactly what is missing ("Add a capital letter and a special character."). Passwords stored before the rule existed keep working; the rule is checked only when a password is chosen.

### Creating the first administrator

When the backend starts and **no active administrator exists**, it creates one from four environment variables. All four must be set:

| Variable | What to put in it |
|---|---|
| `BLOODLINK_BOOTSTRAP_ADMIN_PHONE` | The administrator's phone, e.g. `076000000` or `+232 76 000 000`. This is their sign-in. |
| `BLOODLINK_BOOTSTRAP_ADMIN_NAME` | Their full name. |
| `BLOODLINK_BOOTSTRAP_ADMIN_EMAIL` | Their email address (every staff account needs one). |
| `BLOODLINK_BOOTSTRAP_ADMIN_PASSWORD` | A temporary password meeting [the password rule](#the-password-rule). They must change it at their first sign-in. |

In PowerShell, in the terminal you start the backend from (the values below are placeholders):

```powershell
$env:BLOODLINK_BOOTSTRAP_ADMIN_PHONE    = "076000000"
$env:BLOODLINK_BOOTSTRAP_ADMIN_NAME     = "Your Name"
$env:BLOODLINK_BOOTSTRAP_ADMIN_EMAIL    = "you@example.org"
$env:BLOODLINK_BOOTSTRAP_ADMIN_PASSWORD = "Choose-a-temporary-password-1"
.\mvnw.cmd spring-boot:run
```

The log then says `Created the first administrator account for …`. Sign in with that phone and password, choose your own password, and remove the four variables (`Remove-Item Env:BLOODLINK_BOOTSTRAP_ADMIN_*`). The creation is recorded in the Audit Log.

- **If any active administrator already exists, the settings are ignored.** No existing account is ever changed, reset or reactivated.
- **Nothing is created if a setting is refused:** a phone or email that already belongs to another account, a malformed phone or email, or a password that does not meet the password rule. The log says which setting and why (for a password, what it is missing). The password itself is never written to the log.
- **If no administrator exists and the settings are not set**, the backend still starts, and logs a warning explaining how to create one.
- Never write real values into `application.properties.example`, `.env.example` or any other file in the repository. Two servers starting at the same moment never create two administrators.

## Running the tests

There are three test commands. Run the first two from `backend/` and the third from `frontend/`:

```bash
.\mvnw.cmd test                        # 1. backend unit tests
.\mvnw.cmd test "-Dtest=*IT"           # 2. backend integration tests
npm test                               # 3. frontend tests
```

(On macOS / Linux use `./mvnw` instead of `.\mvnw.cmd`.)

1. **Unit tests** check the backend's rules one piece at a time, with no database: eligibility, stock thresholds, earliest-expiry-first issuing, appeal cooldowns, phone and email formats, the JWT secret checks, and who may do what. They need nothing set up.
2. **Integration tests** (every class whose name ends in `IT`) start the real backend against a real PostgreSQL database and check whole features end to end: requests filled by several banks, write-offs, the daily expiry notices, donation visit bookings, request cancellations, admin accounts and the audit log, reports, the donor roster, donor blood group changes, the sign-in and registration limits, and who receives each notification. They need:
   - a database named `bloodlink_test`, created once: `CREATE DATABASE bloodlink_test;`
   - PostgreSQL on `localhost:5432`. The tests sign in as user `postgres` with password `root` unless you set `BLOODLINK_TEST_DB_USERNAME` and `BLOODLINK_TEST_DB_PASSWORD` in the terminal you run them from. Every integration test uses these two variables, so with neither set nothing changes. In PowerShell, for that terminal only:

     ```powershell
     $env:BLOODLINK_TEST_DB_USERNAME = "your_user"
     $env:BLOODLINK_TEST_DB_PASSWORD = "your_password"
     ```
   - permission for that user to create databases: three tests make a throwaway database of their own (`bloodlink_race_test`, `bloodlink_bootstrap_race_test`, `bloodlink_migration_test`) and delete it when they finish.
3. **Frontend tests** check the wording and logic kept in `frontend/src/lib/`: appeal messages, batch and write-off forms, blood group messages, the booking form, pagination, reports and user-account filters. They use Node's built-in test runner and need nothing set up beyond `npm install`.

The integration tests roll back everything they write, so they are safe to re-run, and they never touch `bloodlink_dev`. Tests must only ever use `bloodlink_test`: if a `SPRING_DATASOURCE_URL` is set in your terminal it would override that, so the tests refuse to start until you remove it (`Remove-Item Env:SPRING_DATASOURCE_URL`). For the same reason, set `SPRING_DATASOURCE_*` per terminal, never permanently.

## Database migrations

The schema is built by Flyway from the numbered files in `backend/src/main/resources/db/migration` (`V1__…` to `V32__…`), run in order. **Once a migration has been applied anywhere, never edit it and never reuse its number.** Flyway stores a checksum of every applied file, and refuses to start if one changes. A change always goes in a new file with the next number, which is V33 now. V25 added `users.must_change_password` for the forced password change above; V26 lets the audit log record a change to a donor's blood group (`DONOR_BLOOD_GROUP_UPDATED`) and changes no data; V27 adds the `donation_bookings` table and three notification types for booking a donation visit; V28 adds the audit action and notification type for an administrator resetting a password, and changes no data; V29 adds `users.password_changed_at` (empty for existing accounts), which signs out sessions made before a password change; V30 adds the `password_reset_requests` table for "Forgot password?", one notification type and one audit action; V31 lets the audit log record a locked phone number (`SIGN_IN_LOCKED`, the one action with no acting user) and changes no data; V32 adds `CANCELLED` and four cancellation columns to `blood_requests`, `audit_log.target_blood_request_id`, and the `REQUEST_CANCELLED` notification type and audit action, and changes no existing row. The move to batch-level stock is complete: V24 dropped the old one-row-per-group stock table, so stock lives only in `blood_inventory_batches`. The demo data lives separately in `db/demo-seed` (`V900` upwards) and is only loaded by the `demo` profile.

## Setting a real JWT secret

The backend signs every login token with a secret, read from the `BLOODLINK_JWT_SECRET` environment variable. Anyone who knows it can forge a login for any account, administrators included, so it must be long, random and private.

> **Never commit the secret,** or paste it into any file in this repository, chat or issue. `.env` files are git-ignored for this reason; `.env.example` files hold placeholders only.
>
> **Changing the secret signs every user out.** Every token signed with the old secret stops working at once, so everyone has to sign in again. That is also how to cut off a secret you think has leaked: generate a new one and restart the backend.

It must be at least 32 bytes (256 bits, what the HS256 signing algorithm requires); the backend refuses to start on anything shorter, under every profile. 48 random bytes in Base64 (64 characters) is recommended.

### Windows (PowerShell)

Generate a secret and set it for **this terminal only**. The value is never shown on screen:

```powershell
$bytes = New-Object byte[] 48
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$env:BLOODLINK_JWT_SECRET = [Convert]::ToBase64String($bytes)
```

To keep it **permanently** for your Windows user, run this straight after the lines above. It applies to terminals (and IntelliJ) opened afterwards, not to ones already open:

```powershell
[Environment]::SetEnvironmentVariable('BLOODLINK_JWT_SECRET', $env:BLOODLINK_JWT_SECRET, 'User')
```

Check that it is set, without printing it:

```powershell
"BLOODLINK_JWT_SECRET is $($env:BLOODLINK_JWT_SECRET.Length) characters long"   # expect 64
```

Then start the backend as usual (`.\mvnw.cmd spring-boot:run`); the "INSECURE JWT SECRET" warning no longer appears. To remove a permanent secret: `[Environment]::SetEnvironmentVariable('BLOODLINK_JWT_SECRET', $null, 'User')`.

### macOS / Linux

```bash
export BLOODLINK_JWT_SECRET="$(openssl rand -base64 48)"
```

(add that line to your shell profile to keep it).

### Docker demo

Docker Compose takes the secret from `.env` (copied from `.env.example`) unless `BLOODLINK_JWT_SECRET` is already set in your environment, which wins. If you set one permanently as above, Compose uses it automatically; otherwise replace the placeholder in your `.env` copy with a value generated as above.

### A real deployment

Run the backend jar with a non-demo profile (for example `SPRING_PROFILES_ACTIVE=prod`), its own database through `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`, `BLOODLINK_ALLOWED_ORIGINS` set to the frontend's address, and its own `BLOODLINK_JWT_SECRET`. Generate a separate secret for each environment; do not reuse your development one. `docker-compose.yml` is for the demo only.

[DEPLOYMENT.md](DEPLOYMENT.md) walks through a free hosted setup step by step (Neon for the database, Render for the backend, Cloudflare Pages for the frontend), including the `prod` profile, the memory settings for a 512 MB server, and how a public demo replaces the published demo password.

## Demo mode

Demo mode starts BloodLink with a realistic, fully populated data set instead of an empty database: a large number of donors, both blood banks stocked, and sample requests and notifications. It is kept entirely separate from your development database.

### With Docker (simplest)

```bash
cp .env.example .env
docker compose up --build
```

Then open <http://localhost:5173>. The database lives inside Docker, so nothing on your machine is touched. Use `docker compose down -v` to throw the demo data away and start fresh.

### Without Docker

Create an empty PostgreSQL database called `bloodlink_demo` and start the backend with the `demo` Spring profile. It defaults to a local `postgres` user; override with the `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` environment variables if yours differ.

```bash
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
```

Then start the frontend as above.

### If you ran demo mode before: start from a fresh demo database

The demo data changed when stock moved to batches (it now seeds 2–3 batches per blood group, including one expired batch). An older demo database no longer matches the demo files, so Flyway refuses to start with a "checksum mismatch" error. Throw the old demo data away and let it be rebuilt:

- **Docker:** `docker compose down -v` (the `-v` deletes the demo data volume), then `docker compose up --build`.
- **Without Docker:** in `psql` (or pgAdmin), run `DROP DATABASE bloodlink_demo;` then `CREATE DATABASE bloodlink_demo;`, and start the backend with the `demo` profile again.

This only ever affects the demo database. Your development database, `bloodlink_dev`, is never touched by demo mode.

### Demo sign-ins

Sign in with a phone number and password. Every demo account shares one password, `BloodLink@2026`, which is published in this repository and is for demo data only.

| Role | Phone number |
|---|---|
| Administrator | `+232 76 900 001` |
| Hospital staff (Makeni Regional Hospital) | `+232 76 900 002` |
| Hospital staff (Holy Spirit Hospital) | `+232 76 900 003` |
| Blood bank staff (Makeni Regional Blood Bank) | `+232 76 900 004` |
| Blood bank staff (Holy Spirit Blood Bank) | `+232 76 900 005` |
| Donor | `+232 76 900 006` |

## Further reading

[`CLAUDE.md`](CLAUDE.md) is the detailed technical specification: the database schema, the business rules behind eligibility, stock thresholds, request fulfilment and notifications, and the project's conventions. It is written for AI-assisted development, but it is the most complete description of how the system behaves.

## Contributors

- **Abdulrahim Jalloh**
- **Assanatu Barrie**

The repository's [contributor graph](https://github.com/abdulrahimj/BloodLink/graphs/contributors) and [commit history](https://github.com/abdulrahimj/BloodLink/commits/main) on GitHub record who changed what, for anyone who wants the verifiable detail.
