
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

### Planned (not implemented yet)

| Area | Feature |
|---|---|
| Source databases | Synchronizing deletions and replacing changed constraints or indexes from the source; collection and comparison rules for DBMSs other than PostgreSQL |
| AI | AI-assisted metadata analysis — column descriptions, term mapping suggestions and schema analysis, always with human review; OpenAI-compatible providers (InfraMesh, vLLM) and streaming responses |

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
| Database | PostgreSQL 18 (default), H2 (local development and tests), Flyway migrations |
| Frontend | React 19 + TypeScript (CSR), Vite 6, React Router 7, TanStack Query 5, TanStack Table 8, React Flow, Primer Octicons / Lucide icons — bundled into the Spring Boot JAR |
| Additional libraries | Apache POI 5 (SAX/SXSSF streaming for Excel), springdoc-openapi |

---

## 4. Architecture

A single Spring Boot server serves both the REST API (`/api/**`) and the React build. There is no separate frontend server in production: the React build is copied into the executable JAR.

```text
Browser ── React SPA (static files in the JAR)
   │
   └── /api/** ── Spring Boot (Security · Session JDBC · JPA) ── PostgreSQL / H2
```

Gradle modules (dependency direction `common ← core ← (system, operation, data, source) ← app`; modules on the same level do not depend on each other):

| Module | Responsibility |
|---|---|
| `lexorma-common` | Base entity, exception handling, paging and Excel utilities |
| `lexorma-core` | Authentication, login, project tenancy, request logging |
| `lexorma-system` | Users, projects, system logs |
| `lexorma-operation` | Announcements |
| `lexorma-data` | Data dictionary, tables, columns, views, physical and logical models, mappings, ERD, DDL, change history, model versions, and source ↔ model mapping, comparison and import |
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
| PostgreSQL | 18 recommended; not required with the H2 profile |
| Docker | Optional (Docker Compose v2.23.1 or later for the bundled Compose file) |
| Ports | Application `9940`, local PostgreSQL `15439` |

Gradle does not need to be installed: the project includes the Gradle wrapper (`./gradlew`).

### Quick start without PostgreSQL

```bash
git clone https://github.com/Lexorma/Lexorma.git lexorma
cd lexorma
./gradlew bootRun --args='--spring.profiles.active=h2-local'
```

Open http://localhost:9940 and sign in with `admin` / `admin`. The password must be changed on first login. The H2 in-memory database is rebuilt on every start.

### With PostgreSQL

```bash
cd docker
docker-compose -p lexorma up -d     # PostgreSQL 18 on localhost:15439
cd ..
./gradlew bootRun                   # local profile
```

### Signing in

1. Enter a username and password.
2. Change the password if it is still the initial one (the seeded `admin` account, or after an administrator reset).
3. Choose one of the projects you are a member of.

A user can be signed in from one place at a time; a later login ends the earlier session. If every project is inactive, an `ADMIN` account can sign in with a recovery login that only allows reactivating a project.

> The default administrator credentials are for initial setup only. Change the password before exposing the application to other users or networks.

---

## 6. Configuration

### Profiles

| Profile | Database | Purpose |
|---|---|---|
| `local` (default) | PostgreSQL at `localhost:15439` | Local development |
| `h2-local` | H2 in-memory | Local development without PostgreSQL |
| `dev` | PostgreSQL at `lexorma-postgres:5432` | Development server (port `18080`) |
| `docker` | PostgreSQL from `DB_*` environment variables | Docker deployment |
| `h2-docker` | H2 file database in `/data` | Standalone Docker deployment |

### Environment variables (`docker` profile)

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `lexorma-postgres` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `lexorma` | Database name |
| `DB_USERNAME` | `lexorma` | Database user |
| `DB_PASSWORD` | `lexorma` | Database password |

The default database name and account are kept from the previous project name so that existing installations keep working. **Set `DB_NAME`, `DB_USERNAME` and `DB_PASSWORD` explicitly for a new installation** (the Docker examples below use `lexorma`). Any Spring property can also be overridden the usual way, for example `SPRING_DATASOURCE_URL` or `LOGGING_LEVEL_COM_LEXORMA=DEBUG`.

### Secret encryption key (optional)

Credentials that Lexorma stores for external systems (external database connection passwords; AI providers store no credentials yet) are encrypted with AES-256-GCM. The key is never stored in the database. **The key is optional**: Lexorma starts and all modeling features (dictionary, models, ERD, DDL, mapping, history) work without it; only storing or using such credentials needs a key.

| Property | Environment variable | Description |
|---|---|---|
| `lexorma.secret.keys` | `LEXORMA_SECRET_KEYS` | One or more keys as `<keyId>:<Base64 of 32 random bytes>`, separated by commas — for example `k1:$(openssl rand -base64 32)` |
| `lexorma.secret.active-key-id` | `LEXORMA_SECRET_ACTIVE_KEY_ID` | Key used for new encryption. Defaults to the first key in the list |

- Without a key the application starts and works normally; only features that store or use such credentials fail with a clear error (`SECRET_KEY_NOT_CONFIGURED`).
- A malformed key (not Base64, not 32 bytes, duplicate or unknown key id) stops the application at startup.
- **Local development**: the `local` and `h2-local` profiles (the default profile is `local`) include a fixed development-only key, so no setup is needed. It is public in this repository: never use it for real data or production passwords. The `dev`, `docker` and `h2-docker` profiles have no key.
- **Your own key**: set `LEXORMA_SECRET_KEYS` (and optionally `LEXORMA_SECRET_ACTIVE_KEY_ID`) as environment variables; they take precedence over the development key. For Docker Compose set them in `docker/lexorma/.env` (see [Docker Compose](#postgresql--application-docker-compose)).
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

In Docker, `127.0.0.1` is the Lexorma container itself: register the address of the host (for example `host.docker.internal` on Docker Desktop) or of the Ollama container, and allow the range it resolves to. Lexorma does not install Ollama or download models. Responses are returned in one piece (no streaming). Timeouts: `lexorma.ai.test-timeout-seconds` (connection test and model list, default 15), `lexorma.external.http.response-timeout-seconds` (calls, default 120).

### Database migrations

Schema and seed data are managed by Flyway (`lexorma-app/src/main/resources/db/migration/{postgresql,h2}`).

- Pending migrations are applied at startup; Hibernate only validates the schema.
- Flyway compares the checksums of applied migration files at startup, so a database created from an older version of a file will not start against a changed one.
- `V0__init.sql` (schema) and `V1__seed_data.sql` (seed data) are the fixed baseline. Later changes are added as new incremental migrations (`V2__source_mapping.sql`, `V3__source_import.sql`, then `V4__…` and so on).
- Applied migration files are not edited. Every schema change is a new versioned file, added for both PostgreSQL and H2 with the same content.
- Exception (2026-11-14): the default welcome announcement in `V1__seed_data.sql` was rewritten to describe the current features. A database created before this change stops at startup with a checksum mismatch for version 1. The change touches only that announcement's seed text, so it is safe to accept: run `flyway repair` (Flyway CLI) against that database once, or back it up and create it again. Such a database keeps its earlier announcement text (system announcements cannot be edited on the screen); a database created again gets the new text.
- Existing databases are upgraded in place by applying the pending migrations; existing data is kept, and an update does not require recreating the database.
- Back up production databases before upgrading.
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
java -jar lexorma-app/build/libs/lexorma.jar --spring.profiles.active=local
```

Frontend development with hot reload (run the backend with `bootRun` at the same time):

```bash
cd lexorma-web
npm ci
npm run dev        # http://localhost:5173 — /api is proxied to http://localhost:9940
npm run typecheck
```

Development tools (local profiles only):

| Tool | URL | Profiles |
|---|---|---|
| Swagger UI | http://localhost:9940/swagger-ui | `local`, `h2-local` |
| H2 console | http://localhost:9940/h2-console | `h2-local` |

Tests run against in-memory H2 by default. To run them against PostgreSQL, point them at an empty database:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/<empty database> \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
LEXORMA_TEST_POSTGRES_URL=jdbc:postgresql://localhost:15439/<empty database> \
./gradlew test -PfrontendPrebuilt=true --rerun
```

---

## 8. Docker

Images are built from the repository root with a multi-stage Dockerfile: a Node stage builds React, a JDK stage builds `lexorma.jar` with the React build included, and the `eclipse-temurin:21-jre` runtime runs it (Node.js is not included). One container serves both the UI and the API.

| Image | Dockerfile | Profile |
|---|---|---|
| `lexorma/lexorma:latest` | `Dockerfile` | `docker` (PostgreSQL) |
| `lexorma/lexorma:h2-latest` | `Dockerfile-h2` | `h2-docker` (H2 file database) |

```bash
docker build -t lexorma/lexorma:latest .
docker build -f Dockerfile-h2 -t lexorma/lexorma:h2-latest .
```

### PostgreSQL + application (Docker Compose)

`docker/lexorma/docker-compose.yml` is the Compose file for running Lexorma. All environment-specific values — database name, account and password, data directories, ports, image and the optional encryption key — are read from `docker/lexorma/.env`, which you create from `.env.example`:

```bash
cd docker/lexorma
cp .env.example .env     # edit .env: at least POSTGRES_PASSWORD for a new installation
docker compose -p lexorma up -d
```

- `.env` is ignored by Git. Without it `docker compose` stops with `Set … in .env (see .env.example)`.
- The defaults in `.env.example` keep the database name, account and data directory (`../volume/lexorma`) of earlier versions of this Compose file, so an existing installation keeps its data.
- **Encryption key (optional)**: needed only to store source database passwords. Leave `LEXORMA_SECRET_KEYS` empty to run without a key. To use one, set `LEXORMA_SECRET_KEYS=prod:<output of openssl rand -base64 32>` (and optionally `LEXORMA_SECRET_ACTIVE_KEY_ID`) in `.env`, then recreate the application container: `docker compose -p lexorma up -d --force-recreate lexorma-app`. The key is passed as a Docker secret (`secrets.<name>.environment`, Docker Compose v2.23.1 or later), mounted at `/run/secrets/lexorma.secret.keys` and read with `SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/`; it is not an environment variable of the application container.
- Do not add `LEXORMA_SECRET_KEYS` to the container's `environment`: it would take precedence over the secret.
- `docker compose config` shows only the variable name of the secret source, not the key. The key is still in `.env` and in the environment of the shell running Compose; protect that host.

### New installation with `docker run`

```bash
docker network create lexorma-network

docker run -d \
  --name lexorma-postgres \
  --network lexorma-network \
  -e POSTGRES_DB=lexorma \
  -e POSTGRES_USER=lexorma \
  -e POSTGRES_PASSWORD=<password> \
  -v ./volume/lexorma:/var/lib/postgresql \
  postgres:18.6

docker run -d \
  --name lexorma-app \
  --network lexorma-network \
  -e DB_HOST=lexorma-postgres \
  -e DB_NAME=lexorma \
  -e DB_USERNAME=lexorma \
  -e DB_PASSWORD=<password> \
  -p 9940:9940 \
  lexorma/lexorma:latest
```

> PostgreSQL 18 uses `/var/lib/postgresql` as the volume mount path.

To store source database passwords, add an encryption key (optional): either `-e LEXORMA_SECRET_KEYS=prod:<key>` (visible in `docker inspect`), or mount a file you create yourself with `-v ./secrets/lexorma.secret.keys:/run/secrets/lexorma.secret.keys:ro -e SPRING_CONFIG_IMPORT=configtree:/run/secrets/` (one line `prod:<key>`, not in Git). `docker run` does not use Compose secrets.

### Standalone with H2

```bash
docker run -d \
  --name lexorma-app \
  -v ./volume/h2:/data \
  -p 9940:9940 \
  lexorma/lexorma:h2-latest
```

Stop containers gracefully (`docker stop`) so the H2 file database is flushed.

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
