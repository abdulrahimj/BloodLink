# Deploying BloodLink for free

BloodLink is three pieces, each on its own free service:

| Piece | Service | What it is there |
|---|---|---|
| Database | **Neon** (free PostgreSQL) | a PostgreSQL 18 project |
| Backend (Spring Boot) | **Render** (free web service) | the `backend/Dockerfile` image: 512 MB memory, 0.1 CPU, asleep after 15 minutes idle |
| Frontend (React) | **Cloudflare Pages** | the static files `npm run build` makes |

Nothing in this repository is a real secret. Every password and key below is a **placeholder in angle brackets**
(`<like-this>`): you generate the real ones yourself and paste them straight into the service's dashboard,
never into a file, a commit, a chat or an issue.

Do the three parts in order: Neon, then Render, then Cloudflare Pages. Allow about an hour the first time.

---

## 0. Decide which site you are building, and choose the names

**A public demo (what this guide assumes).** Render runs the profiles `demo,prod`: the fictional demo data
(about 850 donors, both blood banks stocked, sample requests and notifications, one account per role), with
every hosted safeguard. The demo data's published password `BloodLink@2026` does **not** work on it: at
every start the backend replaces it with two secrets of yours (see [Demo passwords](#demo-passwords)).

**A real, empty site.** Render runs the profile `prod` only, with no demo data. The first administrator
comes from the startup settings (README, "Creating the first administrator"). The differences are
noted at each step, marked **(real site)**.

Pick two names now, because each service needs the other's address:

- a Render service name, e.g. `bloodlink-api`, giving `https://bloodlink-api.onrender.com`
- a Cloudflare Pages project name, e.g. `bloodlink`, giving `https://bloodlink.pages.dev`

If a name is taken, the service adds a suffix. Use the address the dashboard actually shows.

### Generate the secrets (PowerShell, nothing shown on screen)

Each block puts one new random value on the clipboard, ready to paste into Render. Run one block, paste
it, then run the next:

```powershell
# The login-token signing key (BLOODLINK_JWT_SECRET): 48 random bytes, 64 characters.
$bytes = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
[Convert]::ToBase64String($bytes) | Set-Clipboard
```

```powershell
# A demo password (run it twice: once for BLOODLINK_DEMO_PASSWORD, once for BLOODLINK_DEMO_ADMIN_PASSWORD).
# 16 random characters, drawn again until there is a capital, a small letter, a number and a + or /.
do {
  $bytes = New-Object byte[] 12; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  $p = [Convert]::ToBase64String($bytes)
} until ($p -cmatch '[A-Z]' -and $p -cmatch '[a-z]' -and $p -match '[0-9]' -and $p -match '[+/]')
$p | Set-Clipboard
```

Every password the app accepts follows one rule (README, "The password rule"): 8 to 72 characters with a
capital letter, a small letter, a number and a special character. The demo passwords are checked against
it too; the block above always produces one that passes.

Keep the two demo passwords in a password manager: they are what you give examiners. The JWT secret is
never needed again; if it is lost, generate a new one (that signs everyone out, nothing more).

---

## 1. Neon (database)

1. Sign up at <https://neon.tech> and create a **project**.
   - **PostgreSQL version: 18.** The migrations were checked on PostgreSQL 18.6, the version Neon runs.
     They need nothing extra: no extensions and no superuser rights (`gen_random_uuid()`, triggers and
     partial indexes are all built in).
   - **Region:** the one closest to your users, and choose the **same region for Render**
     (for Sierra Leone, Europe (Frankfurt) on both is a sensible choice). Every request crosses between
     the two, so different continents would slow every page.
   - Database name: the default `neondb` is fine.
2. Open **Connect** on the project dashboard. Make sure **Connection pooling is OFF**: the host name must
   **not** contain `-pooler`. This is the **direct** connection, and BloodLink needs it because Flyway,
   which builds the tables at startup, holds a lock that a pooled connection cannot keep.
3. Neon shows a string like
   `postgresql://<neon-user>:<neon-password>@ep-<something>.eu-central-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require`.
   Take it apart into the three Render values:
   - `SPRING_DATASOURCE_URL` = `jdbc:postgresql://ep-<something>.eu-central-1.aws.neon.tech/neondb?sslmode=require`
     (`jdbc:` in front, **no user name or password in it**, `sslmode=require` kept: Neon accepts
     encrypted connections only)
   - `SPRING_DATASOURCE_USERNAME` = `<neon-user>`
   - `SPRING_DATASOURCE_PASSWORD` = `<neon-password>`

You create no tables. On its first start the backend runs every migration (and, for the demo, the demo
data). That first start takes several minutes on the free instance; later starts find the tables in place.

Neon's free compute also sleeps after about 5 minutes without queries and wakes on the next one in well
under a second, so you will barely notice it.

---

## 2. Render (backend)

1. Sign up at <https://render.com> with GitHub, and allow it to read this repository.
2. **New → Web Service**, choose the repository, then:

   | Setting | Value |
   |---|---|
   | Name | `bloodlink-api` (your choice from step 0) |
   | Language | **Docker** |
   | Branch | `main` |
   | Region | the same as Neon |
   | Root Directory | leave empty |
   | Dockerfile Path | `./backend/Dockerfile` |
   | Docker Build Context Directory | `./backend` |
   | Instance Type | **Free** |

3. **Environment variables** (Environment section; "Add from .env" is not needed). Type each name exactly:

   | Name | Value |
   |---|---|
   | `SPRING_PROFILES_ACTIVE` | `demo,prod` **(real site: `prod`)** |
   | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<neon-host>/neondb?sslmode=require` |
   | `SPRING_DATASOURCE_USERNAME` | `<neon-user>` |
   | `SPRING_DATASOURCE_PASSWORD` | `<neon-password>` |
   | `BLOODLINK_JWT_SECRET` | `<64-character secret from step 0>` |
   | `BLOODLINK_ALLOWED_ORIGINS` | `https://<pages-project>.pages.dev` (no slash at the end) |
   | `BLOODLINK_DEMO_PASSWORD` | `<demo password 1>`: every demo account except the administrator **(real site: leave out)** |
   | `BLOODLINK_DEMO_ADMIN_PASSWORD` | `<demo password 2>`: the demo administrator **(real site: leave out)** |
   | `BLOODLINK_SUPPORT_PHONE` | optional: a phone number people can call, shown on Sign in after "Forgot password?" ("Or call BloodLink on …"), exactly as typed; leave out to show nothing |

   **(real site)** add the four first-administrator settings instead of the two demo passwords:
   `BLOODLINK_BOOTSTRAP_ADMIN_PHONE`, `BLOODLINK_BOOTSTRAP_ADMIN_NAME`, `BLOODLINK_BOOTSTRAP_ADMIN_EMAIL`,
   `BLOODLINK_BOOTSTRAP_ADMIN_PASSWORD` (README, "Creating the first administrator"); delete them once
   the administrator has signed in. On the demo they would be ignored, since the demo already has one.

   Do **not** set `PORT` (Render sets it, 10000; the prod profile listens on it) or `JAVA_TOOL_OPTIONS`
   (the Dockerfile sets it; see [Memory and CPU settings](#memory-and-cpu-settings)). Optional, with
   their defaults: `BLOODLINK_JWT_EXPIRATION_MS` (24 hours), `BLOODLINK_EXPIRY_NOTIFICATIONS_CRON`
   (`0 0 6 * * *`, 06:00 UTC daily) and `BLOODLINK_EXPIRY_NOTIFICATIONS_ENABLED` (`true`).

   Render also passes these variables to the image build, but the Dockerfile reads none of them there, so
   no secret ends up inside the image.

4. **Advanced → Health Check Path:** `/api/health`. It answers `{"status":"ok"}` without a sign-in, and
   Render waits for it before sending traffic to a new deploy.
5. Optional, **Advanced → Build Filters → Included paths:** `backend/**`, so a frontend-only commit does
   not rebuild the backend.
6. **Create Web Service.** The first build takes several minutes (Maven downloads everything), and the
   first start about 5 minutes more (measured: 4½ minutes for every migration, the demo data and the start
   on 0.1 CPU; Render allows 15). The service is live when the log shows `Started BloodLinkApplication`
   and the health check turns green.
7. Check it: open `https://<render-service>.onrender.com/api/health` in a browser. Expect `{"status":"ok"}`.

If `BLOODLINK_ALLOWED_ORIGINS` was set before Cloudflare gave you the real address, correct it in
**Environment** afterwards. Saving a variable redeploys the service.

### Logs, memory and restarts on Render

- **Logs** (left menu): everything the backend prints, live and searchable. A refused start is a block
  headed `APPLICATION FAILED TO START` that names the variable to fix. Each start also prints
  `Picked up JAVA_TOOL_OPTIONS: ...`, which confirms the memory settings.
- **Metrics**: memory and CPU over time. Memory should level off around 400 MB (measured: 402 MB peak,
  see [Memory and CPU settings](#memory-and-cpu-settings)), under the 512 MB line. If it ever reaches
  512 MB, Render restarts the service and records it under **Events**. CPU sits at the 0.1 ceiling for
  the whole of a start, which is expected.
- **Events**: each deploy, restart and failure.
- **Manual Deploy → Deploy latest commit** rebuilds; **Restart service** just restarts (on the demo, that
  also re-applies the demo passwords).

### The wake-up delay

A free Render service **goes to sleep after 15 minutes without a request** (an open, signed-in BloodLink tab
keeps it awake, since every portal re-reads its notifications every 30 seconds). The next visitor then waits
while Render starts the container (about a minute, Render says) **and BloodLink itself starts on 0.1 CPU:
about 7½ minutes when measured under the same limits** (see
[Measured locally](#measured-locally-under-the-same-limits)). So expect **5 to 10 minutes** from the first
request to a working site. During that time the sign-in page still loads, since it comes from Cloudflare,
but signing in fails with "Could not reach the BloodLink server". Wait and try again.

Before a demonstration, open `https://<render-service>.onrender.com/api/health` **10–15 minutes early**, wait
for `{"status":"ok"}`, then sign in and leave that tab open so the service stays awake. Avoid "keep-alive" ping services: an always-awake service uses up Render's 750
free hours a month (and Neon's compute allowance) and is then suspended until the next month.

---

## 3. Cloudflare Pages (frontend)

1. Sign up at <https://dash.cloudflare.com>, then **Workers & Pages → Create → Pages → Connect to Git**,
   and choose the repository.
2. Build settings:

   | Setting | Value |
   |---|---|
   | Project name | `<pages-project>` (step 0) |
   | Production branch | `main` |
   | Framework preset | None (or React (Vite)), then set the fields below |
   | Build command | `npm run build` |
   | Build output directory | `dist` |
   | Root directory (advanced) | `frontend` |

3. **Environment variables (advanced)**, for Production:

   | Name | Value |
   |---|---|
   | `VITE_API_BASE_URL` | `https://<render-service>.onrender.com` (no slash at the end, no `/api`) |

   This is the only frontend setting. It is **not secret**: it is written into the JavaScript every visitor
   downloads, which is why nothing secret may ever go into a `VITE_` variable. The real-time WebSocket uses
   the same address plus `/ws`, so there is no second setting. On Cloudflare the build **stops with an
   error** if this is missing or not `https://`, rather than publishing a site that calls `localhost`.
   Node.js 24 is used, from `frontend/.node-version`.
4. **Save and Deploy.** Then open `https://<pages-project>.pages.dev`.
5. Make sure the address Cloudflare shows is exactly the one in Render's `BLOODLINK_ALLOWED_ORIGINS`.

**Refreshing any page works.** The site has no `404.html`, so Cloudflare Pages treats it as a single-page
app and answers every address that is not a real file with `index.html`. Do not add a
`_redirects` rule such as `/* /index.html 200`: it is unnecessary, and Pages reports it as a redirect loop.
(The Docker image's nginx does the same job with `try_files`.)

**Preview deployments** (Cloudflare builds every branch at `https://<hash>.<pages-project>.pages.dev`) are
refused by the backend, because their address is not in `BLOODLINK_ALLOWED_ORIGINS`. That is deliberate.
To try one, add its address to that variable, separated by a comma.

---

## 4. Check the whole site

1. Open the Cloudflare address and sign in as the demo administrator: phone `076900001`, password
   `<demo password 2>`. Then sign out and sign in as blood bank staff (`076900004`), hospital staff
   (`076900002`) and the donor (`076900006`), each with `<demo password 1>`.
   **(real site)** sign in with the first-administrator settings; you are asked to set a new password.
2. Confirm `BloodLink@2026` is refused on every account. That is the published password, and it must not
   work here.
3. As hospital staff, submit a request. A blood bank staff member signed in in another browser sees it
   arrive without reloading: that proves the WebSocket works through Render.

---

## Demo passwords

The demo data (`db/demo-seed`, V900–V906) gives all its fictional accounts the password `BloodLink@2026`,
which anyone can read in this repository. That is fine on your own machine and unacceptable on a public
site. So when the demo runs with any non-local profile (`demo,prod`), the backend:

- **refuses to start, before writing any demo data**, unless both `BLOODLINK_DEMO_PASSWORD` and
  `BLOODLINK_DEMO_ADMIN_PASSWORD` are set, each meeting the password rule (8–72 characters, a capital
  letter, a small letter, a number and a special character), neither of them `BloodLink@2026`, and
  different from each other;
- then, **at every start**, straight after the database migrations and before it accepts a single
  sign-in, sets the demo administrator's password to `BLOODLINK_DEMO_ADMIN_PASSWORD` and every other demo
  account's (staff and donors, every address ending `@blooddemo.example.com`) to `BLOODLINK_DEMO_PASSWORD`.

What follows from that:

- You can give examiners the staff and donor password without also giving them the administrator.
- If a visitor changes a demo account's password in Profile Settings, the next restart undoes it. On the
  free plan that happens whenever the service wakes from sleep, or by **Restart service** on Render.
  Only accounts whose password actually differs are changed, and only those are signed out (a password
  change signs out earlier sessions); everyone else stays signed in across a restart.
- To change a demo password, edit the variable in Render (which redeploys). Old passwords stop working at
  that start, and everyone signed in to the demo is signed out.
- The passwords are never logged: the log shows only how many accounts were changed
  (`Demo passwords set from BLOODLINK_DEMO_PASSWORD (855 accounts changed) and BLOODLINK_DEMO_ADMIN_PASSWORD (1 administrator accounts changed)`;
  on an ordinary restart usually `0`).
- Everything in the demo is fictional (names built from common Sierra Leonean names, reserved
  `example.com` addresses), so the public site holds no real person's data, unless a visitor registers
  with their own details. Its dates were set when the demo data was first loaded, so stock and donations
  slowly age; starting over means a fresh Neon database (drop it in Neon, create a new one, update the
  three `SPRING_DATASOURCE_*` values).
- Locally nothing changes: `SPRING_PROFILES_ACTIVE=demo` alone (and `docker compose up`) still uses
  `BloodLink@2026`.

---

## Memory and CPU settings

The free instance has 512 MB of memory and a tenth of one processor. Java's defaults assume a much bigger
machine, so the settings below are set for the hosted image only: in `backend/Dockerfile`
(`JAVA_TOOL_OPTIONS`, `MALLOC_ARENA_MAX`) and in `application-prod.properties`. Running the backend on your
own machine (`mvnw spring-boot:run`) uses none of them, and `docker-compose.yml` switches the JVM ones off
for the local demo.

| Setting | Where | In plain words |
|---|---|---|
| `-XX:MaxRAMPercentage=40` | Dockerfile | Java's main memory area (the heap, where data lives) may use at most 40% of the 512 MB, about 205 MB, leaving the rest for everything Java needs outside it (its own code, threads, the database driver's buffers). BloodLink's live data was measured at about 60 MB, so this is over three times what it needs; at 50% the heap simply grew into the extra room and the peak was 40 MB higher. |
| `-XX:+UseSerialGC` | Dockerfile | The simplest memory cleaner (garbage collector): one thread, very little memory of its own, ideal for a small, quiet server. |
| `-XX:ActiveProcessorCount=1` | Dockerfile | Tells Java it has one processor, so it does not start pools of helper threads sized for a big machine. |
| `-Xss512k` | Dockerfile | Each thread reserves 512 KB for its call stack instead of 1 MB; BloodLink never calls deeply enough to need more. |
| `-XX:ReservedCodeCacheSize=64m` | Dockerfile | Caps the memory Java uses for the machine code it compiles at 64 MB (the default reserves 240 MB). |
| `-XX:TieredStopAtLevel=1` | Dockerfile | Uses only Java's quick compiler, which costs far less processor time on 0.1 CPU; the code runs slightly slower, which this workload never notices. |
| `-XX:+ExitOnOutOfMemoryError` | Dockerfile | If memory ever does run out, stop at once, so Render restarts a clean server instead of leaving a broken one running. |
| `MALLOC_ARENA_MAX=2` | Dockerfile | Stops the operating system's memory allocator from keeping a separate memory pool per thread, a known cause of creeping memory use in Java containers. |
| Extracted jar (`app.jar` + `lib/`) | Dockerfile | The application is unpacked into ordinary files at build time instead of running from one big archive; reading classes from the archive was measured at twice the startup work and about 70 MB more memory. |
| `server.tomcat.threads.max=20` | prod profile | At most 20 requests are worked on at once (default 200); more simply wait in line for a moment. |
| `spring.datasource.hikari.maximum-pool-size=5` | prod profile | At most 5 open database connections (default 10), each costing memory on both sides; Neon's free plan is small too. |

### Measured locally under the same limits

Measured on 30 Oct 2026 by running the image's exact build steps and JVM settings on 64-bit Linux (WSL Ubuntu,
x86-64, Temurin 25.0.5), with the process held to **512 MB and 10% of one CPU** by the kernel (a cgroup, the
same mechanism containers use), against PostgreSQL 18.6 with `sslmode=require`, profiles `demo,prod`. A real
`docker build` was not run (no Docker on the machine); the steps were the Dockerfile's, one by one.

| What | Measured |
|---|---|
| Peak memory of the Java process (resident, after two full API walkthroughs and 60 simultaneous requests) | **402 MB** of 512 MB |
| Live data in the heap after a full garbage collection | about 60 MB |
| Class metadata (metaspace) | about 98 MB |
| First start on an empty database (all 34 migrations and the demo data, then ready) | **268 s** (4½ minutes); the migrations themselves took 4 s |
| Ordinary start on a database already built (the wake-up case) | **444 s** (7½ minutes): 44 seconds of processor work, stretched tenfold by the limit. Before the jar was extracted it was 641 s |
| 60 simultaneous dashboard reads | all succeeded, about 30 s in total under the CPU limit |

Render's own CPU may be faster or burstier than a strict 10%; its log says exactly how long a start took
(`Started BloodLinkApplication in N seconds`). Two Render limits these numbers sit inside: a **new deploy must
pass its health check within 15 minutes** or it is cancelled (and the old version kept), and a running
service that fails health checks for 60 seconds in a row is restarted. A heavy burst of visitors slows every
answer on 0.1 CPU, the health check's included; a handful of examiners at a time is what this plan suits.

---

## Updating the site

Push to `main`. Render rebuilds and redeploys the backend (new migrations run at its start, on the Neon
database), and Cloudflare rebuilds the frontend. During a backend redeploy the old version keeps serving
until the new one passes its health check.

## When something goes wrong

| What you see | Where | Fix |
|---|---|---|
| `APPLICATION FAILED TO START` … `BLOODLINK_JWT_SECRET` | Render logs | The secret is missing, under 32 bytes, or the public placeholder. Set a generated one. |
| `The hosted demo cannot start: …` | Render logs | A demo password is missing or unusable; the message says which. |
| `Could not resolve placeholder 'BLOODLINK_ALLOWED_ORIGINS'` (or `SPRING_DATASOURCE_URL`, …) | Render logs | That variable is not set on Render. Check the spelling. |
| `Connection to … refused`, `The connection attempt failed`, `password authentication failed` | Render logs | Check the three `SPRING_DATASOURCE_*` values; the URL starts `jdbc:postgresql://` and carries no user name or password. |
| `Unable to obtain connection` … `pooler` or Flyway hangs on a lock | Render logs | The URL is Neon's **pooled** host. Use the direct one (no `-pooler`). |
| Sign-in says "Could not reach the BloodLink server" | site | The backend is asleep or starting (see [The wake-up delay](#the-wake-up-delay)), or `VITE_API_BASE_URL` is wrong. |
| Browser console: "blocked by CORS policy" | site | `BLOODLINK_ALLOWED_ORIGINS` must be exactly the site's address: `https://`, no slash at the end. |
| Notifications only arrive on reload | site | The WebSocket was refused; same fix as CORS. The 30-second re-read still delivers them. |
| Cloudflare build: `VITE_API_BASE_URL is not set` / `must start with https://` | Cloudflare build log | Set the variable for Production (step 3) and retry the deployment. |
