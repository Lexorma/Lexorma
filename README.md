
<p align="center">
  <img src="lexorma-web/public/images/favicon.png" alt="Lexorma Logo" width="120" height="120">
</p>

<h1 align="center">Lexorma</h1>

<p align="center">
  <strong>Data Standards & Modeling Platform</strong>
</p>

## 1. Introduction

Lexorma is an open-source web application for data standardization and metadata management. On top of a standard data dictionary (words, domains and terms) it supports logical and physical data modeling, schema management, ERDs and analysis of database structures.

- **Consistency** — table and column names are built from approved standard words and terms.
- **One model, several views** — the same model is edited in management screens and in ERDs, and exported as DBMS-specific DDL.
- **Traceability** — approvals, logical ↔ physical mappings and a change history show how the model got to its current state.

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
| Physical modeling | Relations, indexes, constraints | Composite foreign keys, indexes, UNIQUE and CHECK constraints, with foreign key target and type validation |
| ERD | Logical and physical ERD | View and edit mode, drag to connect, per-user layout |
| Mapping | Logical ↔ physical mapping | Entity ↔ table and attribute ↔ column mappings with consistency diagnostics |
| DDL | DDL generation | `CREATE TABLE`, keys, indexes, comments and foreign keys for PostgreSQL (default), Oracle, MySQL and MariaDB, with warnings for incomplete definitions |
| History | Model change history | Who changed what and when for the physical model, the logical model and the mappings, with before/after comparison and grouping of changes made in one operation |
| History | Model versions | Named, immutable snapshots of the whole project model created on request; browse a saved version, and compare two versions or a version with the current model (added / removed / modified objects with before/after values) |
| Administration | Users, projects, permissions | Project membership, hierarchical roles, approval rights, announcements, system logs, recovery login when every project is inactive |

### Planned (not implemented yet)

| Area | Feature |
|---|---|
| Reverse engineering | Importing the structure of an existing database into the physical model and ERD |
| AI | AI-assisted metadata analysis — column descriptions, term mapping suggestions and schema analysis, always with human review |

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
| `ADMIN` | Administrator | User management, everything below |
| `MANAGER` | Manager | Approval, announcements (as the manager of the project) |
| `DBA` | Database administrator | Project management (own projects), system logs |
| `USER` | Standard user | Create and change dictionary items and models |
| `VIEWER` | Read-only user | View dictionaries, models, ERDs, mappings, the change history and model versions |
| `NOT_APPROVE` | Unapproved user | Dashboard and announcements only |

Approval and cancellation of approval require approval rights: the `ADMIN` or `MANAGER` role, or being the manager of the current project. Newly registered users start as `NOT_APPROVE`.

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

Gradle modules (dependency direction `common ← core ← (system, operation, data) ← app`):

| Module | Responsibility |
|---|---|
| `lexorma-common` | Base entity, exception handling, paging and Excel utilities |
| `lexorma-core` | Authentication, login, project tenancy, request logging |
| `lexorma-system` | Users, projects, system logs |
| `lexorma-operation` | Announcements |
| `lexorma-data` | Data dictionary, tables, columns, physical and logical models, mappings, ERD, DDL, change history, model versions |
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
| Docker | Optional (Docker Compose v2) |
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

Credentials that Lexorma will store for external systems (external database connections, MCP servers, AI endpoints — planned features) are encrypted with AES-256-GCM. The key is read from the environment and is never stored in the database.

| Variable | Description |
|---|---|
| `LEXORMA_SECRET_KEYS` | One or more keys as `<keyId>:<Base64 of 32 random bytes>`, separated by commas — for example `k1:$(openssl rand -base64 32)` |
| `LEXORMA_SECRET_ACTIVE_KEY_ID` | Key used for new encryption. Defaults to the first key in the list |

- Without a key the application starts and works normally; only features that store or use such credentials fail with a clear error.
- A malformed key (not Base64, not 32 bytes, duplicate or unknown key id) stops the application at startup.
- To rotate, add the new key, make it the active one and keep the old key until the stored credentials have been re-encrypted.
- If a key is lost, the credentials encrypted with it cannot be recovered and must be registered again. Keep the key in a safe place, separate from database backups.

### Database migrations

Schema and seed data are managed by Flyway (`lexorma-app/src/main/resources/db/migration/{postgresql,h2}`).

- Pending migrations are applied at startup; Hibernate only validates the schema.
- Flyway compares the checksums of applied migration files at startup, so a database created from an older version of a file will not start against a changed one.
- **Before the first official release**, the schema is kept in two files (`V0__init.sql`, `V1__seed_data.sql`) that are still edited as the model evolves. A database created by an earlier build may therefore have to be recreated after an update; this is not a safe way to change a database whose data must be kept.
- **From the first official release on**, applied migration files are frozen and every change is added as a new versioned migration.
- Back up production databases before upgrading.

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

```bash
cd docker/lexorma
docker-compose -p lexorma up -d
```

The bundled Compose files keep the database name, account and data directory of existing installations.

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
