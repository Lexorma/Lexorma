
<p align="center">
  <img src="lexorma-web/public/images/favicon.png" alt="Lexorma Logo" width="120" height="120">
</p>

<h1 align="center">Lexorma</h1>

<p align="center">
  <strong>Data Standards & Modeling Platform</strong>
</p>

## 1. Introduction

Lexorma is an open-source web application for data standardization and metadata management. On top of a standard data dictionary (words, domains and terms) it supports logical and physical data modeling, schema management, ERDs, change tracking, and reading the structure of existing databases into the model.

- **Consistency** — table and column names are built from approved standard words and terms.
- **One model, several views** — the same model is edited in management screens and in ERDs, and exported as DBMS-specific DDL.
- **Traceability** — approvals, logical ↔ physical mappings, a change history and named model versions show how the model got to its current state.
- **Existing databases** — the structure of a live database is collected read-only, compared with the model, and only the changes you choose are imported.

---

## 2. Key Features

### Implemented

| Area | Feature | Description |
|---|---|---|
| Data standards | Standard words, domains, terms | Register, approve and search the data dictionary; Excel templates, import and export; usage lookup (which columns and terms use an item) |
| Data standards | Rule-based naming | Table names are generated from approved words plus the project's table prefix; column names come from the abbreviation of a standard term; recommended physical names for logical models |
| Data standards | Standardization quality | Diagnoses columns without a term, column names that differ from the term abbreviation, and domain vs. column type/length mismatches |
| Logical modeling | Models, entities, attributes, identifiers, relationships | Managed in a list screen and in the logical ERD, with approval of entities and relationships |
| Physical modeling | Schemas, tables, columns | Table and column management, bulk column editing, reusable common column sets, Excel import |
| Physical modeling | Relations, indexes, constraints | Composite foreign keys, indexes, UNIQUE and CHECK constraints, with foreign key target and type validation. PostgreSQL index expressions, partial conditions, sort order / NULLS ordering and INCLUDE columns |
| Physical modeling | Views | Views as model objects with their definition, output columns and referenced tables and views, approval and DDL |
| ERD | Logical and physical ERD | View and edit mode, drag to connect, auto layout, per-user layout |
| Mapping | Logical ↔ physical mapping | Entity ↔ table and attribute ↔ column mappings with consistency diagnostics |
| DDL | DDL generation | `CREATE TABLE`, keys, indexes, comments and foreign keys for PostgreSQL (default), Oracle, MySQL and MariaDB, with warnings for incomplete definitions |
| DDL | Combined DDL export | One script per project or schema (schemas → tables → indexes → foreign keys → views), in design mode or approved objects only, with warnings for references outside the scope |
| History | Model change history | Who changed what and when for the physical model, the logical model and the mappings, with before/after comparison and grouping of changes made in one operation |
| History | Model versions | Named, immutable snapshots of the whole project model created on request; browse a saved version, and compare two versions or a version with the current model (added / removed / modified objects with before/after values), as a list or as before/after ERDs |
| Source databases | Connections and collection | Connections to existing databases (passwords encrypted, host and network allow-lists). Metadata collection over JDBC for PostgreSQL, read-only; each completed collection is kept as a point-in-time source catalog with its change history |
| Source databases | Source ERD | Read-only ERD of a collected point in time, drawn from the actual foreign keys |
| Source databases | Mapping and comparison | Map source schemas and tables to the model, then compare source and model table by table (columns, keys, constraints, indexes, comments; PostgreSQL rules) |
| Source databases | Selective import | Preview a plan, then confirm only the chosen items: new tables (with their mapping), new columns, constraints and indexes, and chosen column attributes. Imported objects are unapproved, approved objects are never changed, nothing is deleted or renamed, and the model just before each import is saved as a model version. Source comments are stored separately from business descriptions |
| Source databases | Re-import after a later collection | Each attribute is compared with the value last imported into the model: changes made only in the source can be chosen again, while attributes edited in the model after the import (or changed on both sides) are shown but never overwritten. Objects deleted in the source are reported and kept in the model |
| Administration | Users, projects, permissions | Project membership, hierarchical roles, approval rights, announcements, system logs, recovery login when every project is inactive |
| Administration | External connection security | Outgoing connections to source databases (JDBC) and AI servers (HTTP) are allowed only to destinations an administrator has allowed (connection type, optional host, CIDR range, port); everything else is denied. The actual connection is pinned to the verified addresses, and every connection attempt and policy change is audited |
| Administration | AI providers (optional) | An `ADMIN` registers the AI server Lexorma may call (Ollama for now) and checks it with a connection test, the server's model list and a test prompt. Calls go through Spring AI and only through the external connection security rules; nothing is set up or connected when no provider is registered |
| AI assistant | Metadata query (optional) | Ask the system default AI provider about the current project's registered metadata (schemas, tables, columns, relations, standard words, terms and domains). The AI can only use read-only lookup tools that Lexorma checks against your project and role on every call; it cannot run SQL, change metadata or reach other projects or source databases. The assistant window shows which tools actually ran. Lexorma checks every table, column, relation, dictionary item and change-history entry the answer cites against the project (existence, scope, name, parent table and changes since the lookup) and marks what it could not confirm. Tools include similar-column search (rule-based scores) and change history. Each request stays within a token budget. Models without tool calling still answer from metadata that Lexorma looks up itself (narrower) |
| AI management | AI description drafts and quality evaluation (optional) | Under AI management › AI description proposals, the default AI provider drafts business descriptions of tables and columns (single or bulk in the background) from the registered metadata, with checked evidence, open questions and uncertain points. Nothing changes until an approver approves (optionally after editing); approval writes only the new business description, re-checks whether the cited metadata changed meanwhile, and refuses conflicting edits. Table/column details and the physical ERD show the business description and proposal status. AI management › AI quality evaluation stores the meaning a reviewer confirmed for chosen tables/columns and their judgments of each model's drafts; metrics use only those human judgments. |

### Planned (not implemented yet)

| Area | Feature |
|---|---|
| Source databases | Synchronizing deletions and replacing changed constraints or indexes from the source; collection and comparison rules for DBMSs other than PostgreSQL |
| AI | AI-assisted metadata analysis beyond business-description drafts — quality checks and improvement advice, relationship inference for legacy schemas and schema analysis, always with human review; OpenAI-compatible providers (InfraMesh, vLLM) and streaming responses |

### Data Standardization Rules

- Standard term names and their English abbreviations join words with underscores (`_`) and must have the same number of words — for example `사용_내역_관리` / `USE_DTLS_MNG`.
- An underscore cannot be used inside a standard word or its abbreviation.
- Abbreviation lookups are case-insensitive.

### Roles

Higher roles include the permissions of lower roles. Menus and action buttons a user cannot use are hidden, and every API checks the role again on the server.

```text
ADMIN > MANAGER > DBA > USER > VIEWER > NOT_APPROVE
```

| Role | Description | Main permissions |
|---|---|---|
| `ADMIN` | Administrator | User management, external connection security, everything below |
| `MANAGER` | Manager | Approval, announcements (as the manager of the project) |
| `DBA` | Database administrator | Project management (own projects), system logs |
| `USER` | Standard user | Create and change dictionary items and models |
| `VIEWER` | Read-only user | View dictionaries, models, ERDs, mappings, the change history, model versions and source database information |
| `NOT_APPROVE` | Unapproved user | Dashboard and announcements only |

Approval and cancellation of approval require approval rights: the `ADMIN` or `MANAGER` role, or being the manager of the current project. Managing source databases (connections, collection, mappings and imports) requires the `USER` role and being the manager of the current project, or `ADMIN`. Newly registered users start as `NOT_APPROVE`.

---

## 3. Technology Stack

| Category | Technologies |
|---|---|
| Language / Build | Java 21, Gradle 9.8 (wrapper included, multi-module) |
| Backend | Spring Boot 4.1, Spring Security (form login, session authentication), Spring Session JDBC, Spring Data JPA, QueryDSL 7 (OpenFeign) |
| Database | PostgreSQL only (15 or later, 18 recommended) for Lexorma's own data and for tests, Flyway migrations |
| Frontend | React 19 + TypeScript (CSR), Vite 6, React Router 7, TanStack Query 5, TanStack Table 8, React Flow, Primer Octicons / Lucide icons — bundled into the Spring Boot JAR |
| Additional libraries | Apache POI 5 (SAX/SXSSF streaming for Excel), springdoc-openapi |

---

## 4. Architecture

A single Spring Boot server serves both the REST API (`/api/**`) and the React build. There is no separate frontend server in production: the React build is copied into the executable JAR.

```text
Browser ── React SPA (static files in the JAR)
   │
   └── /api/** ── Spring Boot (Security · Session JDBC · JPA) ── PostgreSQL
```

Gradle modules (dependency direction `common ← core ← (system, operation, data, source) ← app`, plus `data ← ai ← app`; modules on the same level do not depend on each other and `data` never depends on `ai`):

| Module | Responsibility |
|---|---|
| `lexorma-common` | Base entity, exception handling, paging and Excel utilities |
| `lexorma-core` | Authentication, login, project tenancy, request logging |
| `lexorma-system` | Users, projects, system logs |
| `lexorma-operation` | Announcements |
| `lexorma-data` | Data dictionary, tables, columns, views, physical and logical models, mappings, ERD, DDL, change history, model versions, and source ↔ model mapping, comparison and import |
| `lexorma-ai` | AI providers (Ollama), metadata tool calling and the AI assistant, AI business-description proposals and their quality evaluation, and the optional embedded MCP endpoint (`/mcp`). A library module inside the same application — AI is optional and the app runs without any provider |
| `lexorma-source` | Source database connections, metadata collection and the source metadata catalog with its versions and change history, stored separately from the physical model. Collection runs inside Lexorma over JDBC — no separate collector or MCP server is required |
| `lexorma-app` | Spring Boot application, dashboard API, SPA routing, configuration, Flyway migrations; packages the React build into `lexorma.jar` |
| `lexorma-web` | React frontend source (Vite). Not a Gradle module — its `dist/` is copied into the JAR by the `lexorma-app` build |

The Java base package is `com.lexorma`.

---

## 5. Installation and Running

### Requirements

| Component | Requirement |
|---|---|
| Java (JDK) | 21 or later |
| Node.js | 20.19 or later — needed to build the frontend, not at runtime |
| PostgreSQL | **Required.** 15 or later, 18 recommended. Verified with the official images `postgres:18.6` and `postgres:15.20` (full test suite, fresh install, restart); 16 and 17 are within the supported range but have not been verified. The server must be built with ICU (the official image is — the column-name compare key uses the `und-x-icu` collation, independent of the database default locale). Lexorma does not start without it and never falls back to another database |
| Docker | Optional (Docker Compose v2.23.1 or later for the bundled Compose file) |
| Ports | Application `9940`, local PostgreSQL `15439` |

Gradle does not need to be installed: the project includes the Gradle wrapper (`./gradlew`).

### Quick start (Docker Compose)

```bash
git clone https://github.com/Lexorma/Lexorma.git lexorma
cd lexorma
docker build -f Dockerfile -t lexorma/lexorma:latest .
cd lexorma/docker-compose-exam
cp .env.example .env      # set at least POSTGRES_PASSWORD
docker compose -p lexorma up -d
```

Open http://localhost:9940. On first start there are no accounts, so Lexorma asks you to create the system administrator account (username, password, display name) instead of showing the login form; that account becomes a member of every project. See [8. Docker](#8-docker) for the other ways to run it (existing PostgreSQL server, single JAR).

### Local development

```bash
cd lexorma
docker compose -p lexorma up -d     # development PostgreSQL 18 on localhost:15439 (account and password in lexorma/docker-compose.yml)
cd ..
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/lexorma \
SPRING_DATASOURCE_USERNAME=<user from lexorma/docker-compose.yml> \
SPRING_DATASOURCE_PASSWORD=<password from lexorma/docker-compose.yml> \
./gradlew bootRun                   # local profile (the default of bootRun only)
```

No profile — `local` included — provides a database address, user or password: without the `SPRING_DATASOURCE_*` settings the application does not start. Put them in your shell, your IDE run configuration or a file of your own outside Git. Note that `./gradlew bootRun` (DevTools) reports `BUILD SUCCESSFUL` even when the application stops because a setting is missing — read the log; `java -jar` and the Docker image exit with a non-zero code instead.

### Signing in

1. Enter a username and password.
2. Change the password if it is still the initial one (after an administrator reset).
3. Choose one of the projects you are a member of.

A user can be signed in from one place at a time; a later login ends the earlier session. If every project is inactive, an `ADMIN` account can sign in with a recovery login that only allows reactivating a project.

> There is no default account. Until the first administrator account is created, anyone who can reach the server can create it, so create it before exposing the application to other users or networks.

---

## 6. Configuration

### Management database (PostgreSQL)

Lexorma keeps its own data (users, projects, dictionary, models, ERD layouts, mappings, history, sessions, settings) in PostgreSQL. The connection is a required setting with no default value — Spring Boot's standard properties:

| Property | Environment variable | Description |
|---|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<host>:<port>/<database>` — SSL and other PostgreSQL JDBC options go in the query string (for example `?sslmode=verify-full&sslrootcert=/certs/root.crt`) |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | Database user (required) |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | Password, or a Docker secret file named `spring.datasource.password` under `/run/secrets` (read through `SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/`). Not checked by Lexorma itself: the PostgreSQL server decides which authentication it needs |

- Startup stops before any connection when the URL is missing or empty, is not a `jdbc:postgresql:` URL, contains credentials, is malformed, or the user name is missing. The URL is written to the startup log, so the password must be given separately. The URL is checked by its parts, the way the PostgreSQL JDBC driver reads it:
  - **Refused**: user information before the host (`user:password@host`, `user@host`); a query parameter whose name contains `password` in any letter case or URL encoding (`password`, `sslpassword`, `PASSWORD`, `pass%77ord`, empty or repeated); a `password=`/`password:` expression inside a parameter value or the database name (for example `options=-c password=…`).
  - **Malformed**: an invalid host list (`host[:port]`, comma-separated, IPv6 in `[…]`), an invalid `%` encoding, or a parameter name that is not a plain name after decoding once (double encoding).
  - **Accepted**: host or database names and option values that merely contain the word (`password-management-db`, `/password_service`, `ApplicationName=password-service`), SSL options and multi-host URLs.
  - This is a check for credential *expressions*: Lexorma cannot tell whether an arbitrary value is a secret, so never put one in the URL.
- Connection failures (unreachable server, connection refused — for example a password the server needs but did not receive —, authentication failure, missing database, malformed URL) stop the startup with a short explanation. The messages never contain the password or the URL.
- Create the database and an account that may create tables in it first; Flyway creates the schema on the first start.
- The account needs no superuser rights. PostgreSQL must be 15 or later and built with ICU.

### Profiles

Profiles only change application behaviour; **none of them provides database connection settings** — every way of running Lexorma needs the `SPRING_DATASOURCE_*` settings above.

| Profile | Purpose |
|---|---|
| `local` | Local development: the default of `./gradlew bootRun` only (`SPRING_PROFILES_ACTIVE` overrides it), Swagger UI on, DevTools restart off, a public development-only encryption key |
| `dev` | Development server (`Dockerfile.dev`, port `18080`). Its deployment must provide `SPRING_DATASOURCE_URL` and `SPRING_DATASOURCE_USERNAME` (plus `SPRING_DATASOURCE_PASSWORD` when the server asks for one); set `LEXORMA_SECRET_KEYS` to store source passwords (no fixed key in this profile) |
| `docker` | Docker image (`Dockerfile`) |

| How it runs | Profile |
|---|---|
| `./gradlew bootRun` | `local` unless `SPRING_PROFILES_ACTIVE` is set |
| `java -jar lexorma.jar` | none unless given (`--spring.profiles.active=…` or `SPRING_PROFILES_ACTIVE`) |
| Docker images | the `CMD` of the Dockerfile (`docker`, `dev`) or the deployment's settings |
| Tests | `test` (test-only settings, below) |

Any Spring property can be overridden the usual way, for example `LOGGING_LEVEL_COM_LEXORMA=DEBUG`.

### Secret encryption key (optional)

Credentials that Lexorma stores for external systems (external database connection passwords; AI providers store no credentials yet) are encrypted with AES-256-GCM. The key is never stored in the database. **The key is optional**: Lexorma starts and all modeling features (dictionary, models, ERD, DDL, mapping, history) work without it; only storing or using such credentials needs a key.

| Property | Environment variable | Description |
|---|---|---|
| `lexorma.secret.keys` | `LEXORMA_SECRET_KEYS` | One or more keys as `<keyId>:<Base64 of 32 random bytes>`, separated by commas — for example `k1:$(openssl rand -base64 32)` |
| `lexorma.secret.active-key-id` | `LEXORMA_SECRET_ACTIVE_KEY_ID` | Key used for new encryption. Defaults to the first key in the list |

- Without a key the application starts and works normally; only features that store or use such credentials fail with a clear error (`SECRET_KEY_NOT_CONFIGURED`).
- A malformed key (not Base64, not 32 bytes, duplicate or unknown key id) stops the application at startup.
- **Local development**: the `local` profile includes a fixed development-only key, so no setup is needed. It is public in this repository: never use it for real data or production passwords. The `dev` and `docker` profiles have no key.
- **Your own key**: set `LEXORMA_SECRET_KEYS` (and optionally `LEXORMA_SECRET_ACTIVE_KEY_ID`) as environment variables; they take precedence over the development key. For Docker Compose set them in `lexorma/docker-compose-exam/.env` (see [Docker Compose](#postgresql--application-docker-compose)).
- Precedence: command-line arguments > Java system properties > OS environment variables > the profile files inside the JAR (including the development key). A key mounted as a Docker secret and read through `spring.config.import=configtree:` is a config file too, so an environment variable with the same name wins over it. Set the key in one place only.
- To rotate, add the new key, make it the active one and keep the old key until the stored credentials have been re-encrypted: `k2:<new key>,k1:<old key>` with `LEXORMA_SECRET_ACTIVE_KEY_ID=k2`. A running container keeps the old value; recreate it after changing the key.
- If a key is lost, the credentials encrypted with it cannot be recovered and must be registered again. Keep the key in a safe place, separate from database backups.

### External connection access

Lexorma connects to source databases and AI servers itself. Connections are **denied by default**: an `ADMIN` allows destinations under *System › External connection security* with rules of connection type, optional host, CIDR range and port. One rule must match the port, the host (if set) and every address the host name resolves to. Link-local, cloud metadata, multicast and unspecified addresses are always refused, even with a rule. The socket is pinned to the verified addresses, so a DNS change after the check cannot redirect the connection.

After upgrading, existing source connections stay blocked until a rule allows them; the same screen lists registered connections without a rule. Use a numeric address such as `127.0.0.1` instead of `localhost` when it resolves to both IPv4 and IPv6.

The operator can additionally cap source database connections with environment variables. They apply only when set, on top of the administrator rules:

| Variable | Description |
|---|---|
| `LEXORMA_SOURCE_ALLOWED_HOSTS` | Host names or addresses that source connections may use, separated by commas (`*.db.internal` allowed). Empty: no cap |
| `LEXORMA_SOURCE_ALLOWED_NETWORKS` | Private and loopback ranges (CIDR, comma separated) that source connections may reach. Empty: no cap (an administrator rule is still required) |

Storing a connection password requires the secret encryption key above.

### AI providers (optional)

AI is optional: with no provider registered Lexorma needs no AI settings, no Ollama, no API key and no secret key, and it never connects to an AI server on its own. To use a local Ollama:

1. *System › External connection security*: add an `AI HTTP` rule, for example host `127.0.0.1`, range `127.0.0.1/32`, port `11434`.
2. *System › AI provider management*: register type Ollama with the address `http://127.0.0.1:11434` (saving does not connect).
3. *Check*: run the connection test, pick the default model from the server's model list and try a test prompt.
4. *Set as default*: AI features use the **system default provider** and its default model. Registering a provider does not make it the default; only an enabled provider with a default model can be set. A disabled default stays selected but is not called, and Lexorma never switches to another provider on its own. Clear the default (or set another one) before deleting that provider.
5. *AI assistant* (the button at the bottom right, for `VIEWER` and above; it appears only while the system default provider is usable): ask a question about the current project. The default model must support tool calling (for example a model whose Ollama capabilities include `tools`); otherwise the query fails with `AI_TOOL_CALLING_UNSUPPORTED`. Questions, answers and looked-up metadata are sent to that AI server and are not stored.

In Docker, `127.0.0.1` is the Lexorma container itself: register the address of the host (for example `host.docker.internal` on Docker Desktop) or of the Ollama container, and allow the range it resolves to. Lexorma does not install Ollama or download models. Responses are returned in one piece (no streaming). Timeouts: `lexorma.ai.test-timeout-seconds` (connection test and model list, default 15), `lexorma.external.http.response-timeout-seconds` (calls, default 120).

### Database migrations

Schema and seed data are managed by Flyway (`lexorma-app/src/main/resources/db/migration/postgresql`).

- Pending migrations are applied at startup; Hibernate only validates the schema. A failed migration stops the startup; Lexorma never runs Flyway `repair`, `clean` or `baseline` by itself.
- Flyway compares the checksums of applied migration files at startup. Applied migration files are not edited: every schema change is a new versioned file (`V4__…` next).
- Existing databases are upgraded in place by applying the pending migrations; existing data is kept. Back up the database (`pg_dump`) before upgrading.
- In the `local` profile, Spring Boot DevTools automatic restart is turned off (`spring.devtools.restart.enabled: false` in `application-local.yml`). Otherwise a Gradle build or test run while the application is running from the IDE copies new migration files into `build/resources` and the restart applies them to the local database without notice. New migrations are applied to the local database only when you start the application yourself.
- Before starting the application against a local database that has data you want to keep, check new migration files on a copy first (for example `pg_dump` into a scratch database and start the application against it with `SPRING_DATASOURCE_URL`). To turn automatic restart back on for a session, add `--spring.devtools.restart.enabled=true` to the run configuration.

### Sessions

Login sessions are stored in the database (Spring Session JDBC), so users stay signed in across restarts until the session expires (30 minutes). The same image works over HTTP and HTTPS; behind a TLS-terminating proxy, forward the `X-Forwarded-Proto` header.

### Brand assets

The icon and share image live in `lexorma-web/public/images` and are served under `/images/**`: `favicon.svg` (source; also the logo on the login, sign-up, header and footer), `favicon.png` (512×512), `apple-touch-icon.png` (180×180) and `og-image.png` (1200×630). The PNG files are rendered from `favicon.svg`; replace all four together. There is no web app manifest (the app is not a PWA).

---

## 7. Development and Build

```bash
./gradlew clean build              # npm ci + React build + all module tests
./gradlew :lexorma-app:bootJar     # executable JAR with the React build included
./gradlew :lexorma-app:bootJar -PfrontendPrebuilt=true   # use an existing lexorma-web/dist
./gradlew bootRun                  # run on http://localhost:9940 (local profile)
./gradlew test --tests 'com.lexorma.SomeTest'             # a single test
```

The executable JAR is written to `lexorma-app/build/libs/lexorma.jar`:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/lexorma SPRING_DATASOURCE_USERNAME=<user> SPRING_DATASOURCE_PASSWORD=<password> \
java -jar lexorma-app/build/libs/lexorma.jar --spring.profiles.active=local
```

Frontend development with hot reload (run the backend with `bootRun` at the same time):

```bash
cd lexorma-web
npm ci
npm run dev        # http://localhost:5173 — /api is proxied to http://localhost:9940
npm run typecheck
```

Development tools (`local` profile only):

| Tool | URL | Profiles |
|---|---|---|
| Swagger UI | http://localhost:9940/swagger-ui | `local` |

Tests that need a database run against PostgreSQL only, configured with test-only variables (the normal `SPRING_DATASOURCE_*` settings and profiles are not used; a test context refuses to start when they point elsewhere). Use an empty database whose name contains `test` and preferably a test-only account — the tests create the schema, example data and many test rows:

```bash
LEXORMA_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:15439/lexorma_test \
LEXORMA_TEST_POSTGRES_USER=<test user> \
LEXORMA_TEST_POSTGRES_PASSWORD=<its password> \
LEXORMA_TEST_SOURCE_POSTGRES_URL=jdbc:postgresql://127.0.0.1:15439/postgres \
LEXORMA_TEST_SOURCE_POSTGRES_USER=<user that may create databases> \
LEXORMA_TEST_SOURCE_POSTGRES_PASSWORD=<its password> \
./gradlew test -PfrontendPrebuilt=true --rerun
```

- Without `LEXORMA_TEST_POSTGRES_URL`/`_USER` every test that needs the database fails with a message naming the variables — tests that start Spring and tests that connect directly (migration tests) alike; nothing is skipped silently. Unit tests without a database still pass (for example `./gradlew :lexorma-data:test`).
- The test database is checked before Flyway or any connection: a `jdbc:postgresql:` URL, a database name containing `test`, and the effective Spring URL/user must be the test ones (the normal `SPRING_DATASOURCE_*` must not override them). The name rule is only an extra safeguard — use a database and account that exist only for tests.
- `LEXORMA_TEST_SOURCE_POSTGRES_URL` enables the source-database collection tests (19); without it only they are skipped. With it, `LEXORMA_TEST_SOURCE_POSTGRES_USER` is required (there is no default account) and must be allowed to create databases; the URL must point to a different database than the management test database (for example the `postgres` database of the same server) and its host to a single address (use `127.0.0.1`, not `localhost`).
- Create a new empty test database for each full run (`dropdb`/`createdb`): the tests' fixed ids collide with rows of an earlier run.
- Performance measurements run only with `LEXORMA_PERF=true`.

**CI** (`.github/workflows/dev-test.yml`, on pushes to `dev`): `./gradlew build` with a `postgres:18.6` service container — the management test database `lexorma_test` and, on the same server, the source collection tests through the `postgres` database, with throw-away CI-only credentials. On failure the test reports and the PostgreSQL log are uploaded. A second job only builds the production image from `Dockerfile` and checks that its JAR contains the React build and the PostgreSQL driver and no H2 library; it pushes nothing.

---

## 8. Docker

Images are built from the repository root with a multi-stage Dockerfile: a Node stage builds React, a JDK stage builds `lexorma.jar` with the React build included, and the `eclipse-temurin:21-jre` runtime runs it (Node.js is not included). One container serves both the UI and the API.

| Image | Dockerfile | Profile |
|---|---|---|
| `lexorma/lexorma:latest` | `Dockerfile` | `docker` |

```bash
docker build -t lexorma/lexorma:latest .
```

Lexorma always needs a PostgreSQL server (15 or later, 18 recommended, built with ICU; verified with 15.20 and 18.6). Three ways to run it:

| | Lexorma | PostgreSQL |
|---|---|---|
| A. Docker Compose | container | container from the same Compose file |
| B. Existing PostgreSQL | container (`docker-compose.external-db.yml` or `docker run`) | your server |
| C. Single JAR | `java -jar lexorma.jar` | your server |

### PostgreSQL + application (Docker Compose)

`lexorma/docker-compose-exam/docker-compose.yml` is the Compose file for running Lexorma with its own PostgreSQL container. All environment-specific values — database name, account and password, data directory, ports, image and the optional encryption key — are read from `lexorma/docker-compose-exam/.env`, which you create from `.env.example`:

```bash
cd lexorma/docker-compose-exam
cp .env.example .env     # set POSTGRES_PASSWORD (required, no default)
docker compose -p lexorma up -d
```

- `.env` is ignored by Git. A missing or empty required value stops `docker compose` with `Set … in .env (see .env.example)`.
- The database name, account and password are entered once; the application container receives them as `SPRING_DATASOURCE_*`.
- The PostgreSQL container keeps its data in `POSTGRES_DATA_DIR`. Do not point a new PostgreSQL major version at a data directory created by another one.
- **Encryption key (optional)**: needed only to store source database passwords. Leave `LEXORMA_SECRET_KEYS` empty to run without a key. To use one, set `LEXORMA_SECRET_KEYS=prod:<output of openssl rand -base64 32>` (and optionally `LEXORMA_SECRET_ACTIVE_KEY_ID`) in `.env`, then recreate the application container: `docker compose -p lexorma up -d --force-recreate lexorma-app`. The key is passed as a Docker secret (`secrets.<name>.environment`, Docker Compose v2.23.1 or later), mounted at `/run/secrets/lexorma.secret.keys` and read with `SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/`; it is not an environment variable of the application container.
- Do not add `LEXORMA_SECRET_KEYS` to the container's `environment`: it would take precedence over the secret.
- `docker compose config` shows only the variable name of the secret source, not the key. The key is still in `.env` and in the environment of the shell running Compose; protect that host.

### Existing PostgreSQL server (application only)

With Docker Compose, set the standard Spring Boot keys in `.env` — `SPRING_DATASOURCE_URL` and `SPRING_DATASOURCE_USERNAME` (required) and `SPRING_DATASOURCE_PASSWORD` — and use the application-only file:

```bash
cd lexorma/docker-compose-exam
docker compose -f docker-compose.external-db.yml -p lexorma up -d
```

Or with `docker run`:

```bash
docker run -d \
  --name lexorma-app \
  -e SPRING_DATASOURCE_URL='jdbc:postgresql://db.example.internal:5432/lexorma?sslmode=require' \
  -e SPRING_DATASOURCE_USERNAME=lexorma \
  -e SPRING_DATASOURCE_PASSWORD=<password> \
  -p 9940:9940 \
  lexorma/lexorma:latest
```

- The password follows the server's authentication method: set it for password authentication; remove the line (no password) or leave it empty when the server does not ask for one (for example client-certificate authentication, mounted with an extra volume and given with `sslcert`/`sslkey` options). A password the server needs but does not get stops the startup ("connection refused" / "authentication failed").
- From a container, `localhost` is the container itself: use the server's host name (on Docker Desktop `host.docker.internal`).
- To keep the password out of `docker inspect`, mount it as a file instead of `SPRING_DATASOURCE_PASSWORD`: `-v ./secrets/db-password:/run/secrets/spring.datasource.password:ro -e SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/`.
- Certificates for `sslrootcert` and similar options are mounted with an extra `-v`.

### Single JAR

```bash
./gradlew :lexorma-app:bootJar
SPRING_DATASOURCE_URL=jdbc:postgresql://db.example.internal:5432/lexorma \
SPRING_DATASOURCE_USERNAME=lexorma \
SPRING_DATASOURCE_PASSWORD=<password> \
java -jar lexorma-app/build/libs/lexorma.jar
```

### Encryption key with `docker run`

To store source database passwords, add an encryption key (optional): either `-e LEXORMA_SECRET_KEYS=prod:<key>` (visible in `docker inspect`), or mount a file you create yourself with `-v ./secrets/lexorma.secret.keys:/run/secrets/lexorma.secret.keys:ro -e SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/` (one line `prod:<key>`, not in Git). `docker run` does not use Compose secrets.

### Backup

Back up the PostgreSQL database with `pg_dump` (for example `docker exec lexorma-postgres pg_dump -U lexorma -Fc lexorma > lexorma.dump`) and restore it with `pg_restore` into an empty database. Keep the encryption key separately.

---

## 9. License

Lexorma is licensed under the [MIT License](LICENSE).

You are free to use, modify and distribute the project, provided that the original copyright notice and license terms are preserved.

---

<p align="center">
  <strong>Lexorma</strong>
</p>

<p align="center">
  <em>Model. Standardize. Understand.</em>
</p>
