# BloodLink

BloodLink is a web-based system for real-time blood bank logistics and voluntary donor management in Makeni City, Sierra Leone. It puts donors, hospitals, blood banks and a system administrator on one platform, so blood stock, donor eligibility and hospital requests are visible as they change instead of being tracked by hand at each facility.

It was built as a final-year BSc Computer Science dissertation project.

## Key features

- **Donor registration and eligibility tracking.** Donors register with their phone number (email is optional), see their own profile and donation history, and are shown whether they are currently eligible to give. Eligibility is enforced as a safety rule: someone still recovering from a recent donation is never sent an appeal and cannot have a donation recorded.
- **Real-time inventory across multiple blood banks.** Each blood bank manages its own stock by blood group, with low and critical levels flagged, batches tracked with their expiry, and donations recorded straight into inventory. Hospitals can see live availability across banks.
- **Hospital blood requests with cooperative multi-bank fulfilment.** A hospital raises a request; any blood bank with stock can contribute part or all of it, and several banks can together cover a request no single bank could. Both sides see progress, such as how many units have arrived and from which banks.
- **Real-time notifications.** Low-stock alerts, new requests, request progress and donation appeals are pushed to the right people as they happen, and are also stored so nothing is lost if someone was offline.
- **Donation appeals.** A blood bank running short can appeal to donors of the blood group it needs, reaching only those who are eligible to give.
- **Admin console.** Administrators create and manage staff accounts, add blood banks and hospitals, review a permanent audit log of their own actions, and see aggregate reports on donations and request outcomes.

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

The login-token signing secret has a public placeholder default that is fine on your own machine (the backend prints a warning at startup to remind you). To use your own, set the `BLOODLINK_JWT_SECRET` environment variable. The backend deliberately refuses to start on the placeholder under any profile other than the default and `demo`, so a real deployment cannot run on it by accident.

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

### Running the tests

```bash
cd backend
.\mvnw.cmd test                        # unit tests
.\mvnw.cmd test "-Dtest=*IT"           # integration tests (need a local database named bloodlink_test)

cd ../frontend
npm test                               # frontend tests
```

The integration tests use their own `bloodlink_test` database and roll back everything they write, so they are safe to re-run. They never touch `bloodlink_dev`. Tests must only ever use `bloodlink_test`: if a `SPRING_DATASOURCE_URL` is set in your terminal it would override that, so the tests refuse to start until you remove it (`Remove-Item Env:SPRING_DATASOURCE_URL`). For the same reason, set `SPRING_DATASOURCE_*` per terminal, never permanently.

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

If you ran demo mode before, throw that old demo database away first (`docker compose down -v`, or drop `bloodlink_demo`). The demo data is rebuilt from scratch on every fresh database, and an older one will not match it.

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
