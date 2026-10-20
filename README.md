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

Open the new `application.properties` and replace `DB_USERNAME` and `DB_PASSWORD` with your own local PostgreSQL credentials. This file is git-ignored, so your credentials stay on your machine.

The login-token signing secret has a public placeholder default that is fine on your own machine (the backend prints a warning at startup to remind you). To use your own, set the `BLOODLINK_JWT_SECRET` environment variable. The backend deliberately refuses to start on the placeholder under any profile other than te default and `demo`, so a real deployment cannot run on it by accident.

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

The integration tests use their own `bloodlink_test` database and roll back everything they write, so they are safe to re-run. They never touch `bloodlink_dev`.

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
