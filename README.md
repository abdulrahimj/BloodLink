# BloodLink

BloodLink is a web-based system for real-time blood bank logistics and voluntary donor management in Makeni City, Sierra Leone. It puts donors, hospitals, blood banks and a system administrator on one platform, so blood stock, donor eligibility and hospital requests are visible as they change instead of being tracked by hand at each facility.

It was built as a final-year BSc Computer Science dissertation project.

## Key features

- **Donor registration and eligibility tracking.** Donors register with their phone number (email is optional), see their own profile and donation history, and are shown whether they are currently eligible to give. Eligibility is a safety rule: someone still in the 90-day recovery period after a donation is never sent an appeal and cannot have a donation recorded.
- **Stock held batch by batch, each with its own expiry date.** Every new delivery ("Add New Batch") and every recorded donation becomes its own batch. Only usable blood counts as stock: once a batch passes its expiry date it is no longer counted, shown as available or issued anywhere. It is listed separately as "expired, awaiting write-off". Low and critical levels are judged on usable units only. Hospitals see live usable totals across all banks.
- **Earliest expiry first.** When a bank sends units to a hospital, they are taken from the batch that expires soonest, then the next. Each batch records exactly where its units went.
- **Writing off stock.** Bank staff can remove units from a batch that has expired, been damaged, failed testing or was entered by mistake. They give a reason, and each write-off goes into the permanent audit log.
- **Daily expiry reminders.** Every morning, each bank's staff are told which batches expire in 7 days. Each day after a batch expires, while it still holds units, they are reminded to write it off.
- **Hospital blood requests filled by several banks together.** A hospital raises a request; any blood bank with stock can contribute part or all of it, so two banks can cover a request neither could alone. Both sides see progress, such as how many units have arrived and from which banks.
- **Donation appeals that donors can answer.** A blood bank running short can appeal to donors of the blood group it needs. Only eligible donors are asked, and a bank cannot repeat an appeal too often. A donor can accept an appeal from their portal, and the bank's staff are told straight away.
- **Real-time notifications.** Low-stock alerts, new requests, request progress, appeals, appeal acceptances and expiry reminders are pushed to the right people as they happen. They are also stored, so nothing is lost if someone was offline. Notifications go to active accounts only: a suspended account receives none.
- **Admin console.** Administrators create and manage staff accounts, add blood banks and hospitals, read a permanent audit log of their own actions and of stock write-offs, and see reports with CSV downloads. Administrators have read-only oversight of stock: they can see every bank's stock, batches and batch history, but cannot add stock, record donations, contribute units to requests or write units off. Only a bank's own staff can change its stock.

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

Only donors can register themselves. Hospital staff, blood bank staff and administrators are created by an administrator inside the app, so a fresh, empty database has no administrator to sign in as. The quickest way to explore every role is the demo mode below, which comes with one account per role already in place.

## Running the tests

There are three test commands. Run the first two from `backend/` and the third from `frontend/`:

```bash
.\mvnw.cmd test                        # 1. backend unit tests
.\mvnw.cmd test "-Dtest=*IT"           # 2. backend integration tests
npm test                               # 3. frontend tests
```

(On macOS / Linux use `./mvnw` instead of `.\mvnw.cmd`.)

1. **Unit tests** check the backend's rules one piece at a time, with no database: eligibility, stock thresholds, earliest-expiry-first issuing, appeal cooldowns, phone and email formats, the JWT secret checks, and who may do what. They need nothing set up.
2. **Integration tests** (every class whose name ends in `IT`) start the real backend against a real PostgreSQL database and check whole features end to end: requests filled by several banks, write-offs, the daily expiry notices, admin accounts and the audit log, reports, the donor roster, and who receives each notification. They need:
   - a database named `bloodlink_test`, created once: `CREATE DATABASE bloodlink_test;`
   - PostgreSQL on `localhost:5432`. The tests sign in as user `postgres` with password `root` unless you set `BLOODLINK_TEST_DB_USERNAME` and `BLOODLINK_TEST_DB_PASSWORD` in the terminal you run them from. Every integration test uses these two variables, so with neither set nothing changes. In PowerShell, for that terminal only:

     ```powershell
     $env:BLOODLINK_TEST_DB_USERNAME = "your_user"
     $env:BLOODLINK_TEST_DB_PASSWORD = "your_password"
     ```
   - permission for that user to create databases: two tests make a throwaway database of their own (`bloodlink_race_test`, `bloodlink_migration_test`) and delete it when they finish.
3. **Frontend tests** check the wording and logic kept in `frontend/src/lib/`: appeal messages, batch and write-off forms, pagination, reports and user-account filters. They use Node's built-in test runner and need nothing set up beyond `npm install`.

The integration tests roll back everything they write, so they are safe to re-run, and they never touch `bloodlink_dev`. Tests must only ever use `bloodlink_test`: if a `SPRING_DATASOURCE_URL` is set in your terminal it would override that, so the tests refuse to start until you remove it (`Remove-Item Env:SPRING_DATASOURCE_URL`). For the same reason, set `SPRING_DATASOURCE_*` per terminal, never permanently.

## Database migrations

The schema is built by Flyway from the numbered files in `backend/src/main/resources/db/migration` (`V1__…` to `V24__…`), run in order. **Once a migration has been applied anywhere, never edit it and never reuse its number.** Flyway stores a checksum of every applied file, and refuses to start if one changes. A change always goes in a new file with the next number, which is V25 now. The move to batch-level stock is complete: V24 dropped the old one-row-per-group stock table, so stock lives only in `blood_inventory_batches`. The demo data lives separately in `db/demo-seed` (`V900` upwards) and is only loaded by the `demo` profile.

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
