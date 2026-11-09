
<p align="center">
  <img src="metamora-web/public/images/favicon.png" alt="MetaMora Logo" width="120" height="120">
</p>

<h1 align="center">MetaMora</h1>

<p align="center">
  <strong>Open Source Data Modeling & Metadata Management Platform</strong>
</p>

MetaMora is an open-source web application designed to streamline database modeling, data standardization, and metadata management.

It provides standardized data dictionaries (words, domains, and terms), rule-based naming conventions, and DDL generation to maintain consistency across database schemas.


MetaMora aims to improve developer productivity by enforcing consistent naming standards and automating repetitive tasks in large-scale software development.

The platform is evolving toward AI-assisted database reverse engineering, metadata analysis, and intelligent data standardization.

---

## Project Goals

1. **Improve Consistency** — Generate consistent table and column names based on standardized data definitions and naming conventions.
2. **Automate DDL Generation** — Generate database-specific DDL statements from registered data standards and table definitions.
3. **Increase Developer Productivity** — Reduce repetitive work through Excel templates and automated workflows.
4. **Centralize Metadata Management** — Establish a unified foundation for managing database structures, data definitions, and standardization rules.
5. **Enable AI-Assisted Metadata Management** — Extend the platform with AI-powered schema analysis, reverse engineering, and metadata intelligence.

---

## Key Features

| Category | Feature | Description |
|---|---|---|
| Dashboard | Dashboard | Pending approvals, recent activities, and announcements |
| Operations | Announcements | Create and view project-specific announcements with read status tracking |
| System Management | Users / Projects / System Logs | User approval and authorization, project configuration (table prefixes and target databases), and API request logs |
| Data Management | Data Dictionary | Manage and approve standardized words, domains, and terms; Excel import/export with common standard terminology templates |
| Data Management | Table Management | Generate table names from standardized English abbreviations, view table details, and generate DDL |
| Data Management | Column Management | Register columns using standardized terms, search and select referenced (FK) tables, perform bulk editing, manage reusable column sets, and import Excel files |

### Data Standardization Rules

- Standard term names and English abbreviations must use underscores (`_`) as word separators.
- The number of words in the standard term and its English abbreviation must match.

  Example:
  - Standard term: `사용_내역_관리`
  - English abbreviation: `USE_DTLS_MNG`

- Underscores (`_`) are reserved as word separators and cannot be used within individual standardized words or English abbreviations.
- Searches for English abbreviations are case-insensitive.

### DDL Generation

MetaMora supports database-specific DDL generation based on the target database configured for each project.

**Supported databases:**

- PostgreSQL (default)
- Oracle
- MySQL
- MariaDB

From **Table Details → View DDL**, users can generate:

- `CREATE TABLE` statements
- Primary key constraints
- Unique constraints
- Indexes
- Table and column comments
- Foreign key constraints

The DDL generator also provides validation warnings for potentially incomplete definitions, such as unspecified column lengths or missing primary keys.

---

## Authorization

MetaMora uses hierarchical role-based access control (RBAC).

Higher-level roles inherit the permissions of lower-level roles. Menus that users cannot access are automatically hidden from the sidebar.

```text
ADMIN > MANAGER > DBA > USER > VIEWER > NOT_APPROVE
```

| Role | Description | Main Permissions |
|---|---|---|
| `ADMIN` | Administrator | Full system access, including user management |
| `MANAGER` | Manager | Approval workflows, announcement creation and modification |
| `DBA` | Database Administrator | Project management, system logs, and data dictionary approval |
| `USER` | Standard User | Column management |
| `VIEWER` | Read-Only User | View data dictionaries and tables |
| `NOT_APPROVE` | Unapproved User | Dashboard and announcements only |

Newly registered users are assigned the `NOT_APPROVE` role until an administrator grants additional permissions.

---

## Technology Stack

| Category | Technologies |
|---|---|
| Language / Build | Java 21, Gradle 9.8 (Wrapper included, multi-module project) |
| Backend | Spring Boot 4.1, Spring Security (session authentication), Spring Session JDBC, Spring Data JPA, QueryDSL 7 (OpenFeign) |
| Database | PostgreSQL 18 (default), H2 (local development and testing), Flyway migrations |
| Frontend | React 19 + TypeScript (CSR), Vite 6, React Router 7, TanStack Query 5, Primer Octicons / Lucide icons, GitHub Primer styling, Pretendard font — bundled into the Spring Boot JAR |
| Additional Libraries | Apache POI 5 (SAX/SXSSF streaming for Excel), springdoc-openapi |

### Module Architecture

MetaMora follows a Gradle multi-module architecture to separate shared infrastructure, core services, and domain-specific functionality.

**Dependency structure:**

```text
common ← core ← (system, operation, data) ← app
```

| Module | Responsibility |
|---|---|
| `metamora-common` | Shared components, BaseEntity, grid utilities, exception handling, and common utilities |
| `metamora-core` | Authentication, login, project tenancy, and request logging |
| `metamora-system` | System administration, user management, project configuration, and system logs |
| `metamora-operation` | Operational management and announcements |
| `metamora-data` | Data dictionaries, tables, columns, reusable column sets, and Excel template downloads |
| `metamora-app` | Spring Boot application, dashboard API, SPA routing, Flyway migrations; packages the React build into `metamora.jar` |
| `metamora-web` | React frontend source (Vite). Not a Gradle module — its `dist/` is copied into the JAR by the `metamora-app` build |
---

## System Requirements

| Component | Requirement |
|---|---|
| Java (JDK) | **21 or later** |
| Node.js | **20.19 or later** (24 LTS recommended) — needed to build the frontend; not needed at runtime |
| PostgreSQL | PostgreSQL 18 recommended (`postgres:18.6` in Docker Compose); not required when using the H2 profile |
| Docker | Optional (Docker Compose v2) |
| Application Port | `9940` |
| PostgreSQL Port | `15439` |

> Gradle does not need to be installed separately because the project includes the Gradle Wrapper (`./gradlew`).

---

## Getting Started

### 1. Clone the Repository

```bash
git clone <repository-url> metamora
cd metamora
```

### 2. Set Up PostgreSQL

Start PostgreSQL using Docker Compose:

```bash
cd docker
docker-compose -p metamora up -d
```

The default local PostgreSQL configuration is:

| Property | Value |
|---|---|
| Host | `localhost` |
| Port | `15439` |
| Database | `metamora` |
| Username | `metamora` |
| Password | `metamora` |

Alternatively, install PostgreSQL from the [official website](https://www.postgresql.org/download/), configure port `15439`, and create the same database and credentials.

### 3. Build and Run

```bash
# Build (npm ci + React build + JAR) and run tests
./gradlew clean build

# Build the executable JAR only (React is built and included automatically)
./gradlew :metamora-app:bootJar

# Use an already built metamora-web/dist instead of running npm (fails if dist is missing)
./gradlew :metamora-app:bootJar -PfrontendPrebuilt=true

# Start with the local profile (PostgreSQL)
./gradlew bootRun
```

Frontend development with hot reload (run the backend with `bootRun` at the same time):

```bash
cd metamora-web
npm ci
npm run dev        # http://localhost:5173 — /api is proxied to http://localhost:9940
```

To run MetaMora without PostgreSQL, use the H2 in-memory database:

```bash
./gradlew bootRun --args='--spring.profiles.active=h2-local'
```

> The H2 in-memory database is reset whenever the application restarts (including login sessions).

Login sessions are stored in the database (Spring Session JDBC, `SPRING_SESSION` tables created by the Flyway `V0__init.sql` migration), so a logged-in user stays logged in across application restarts and container re-creation as long as the database is kept and the session has not expired (30 minutes).

The executable JAR is generated at:

```text
metamora-app/build/libs/metamora.jar
```

Run the application directly:

```bash
java -jar metamora-app/build/libs/metamora.jar --spring.profiles.active=local
```

### 4. Access the Application

Once the application starts, open:

**Application:** http://localhost:9940

The following development tools are available only with local profiles:

| Tool | URL | Available Profiles |
|---|---|---|
| Swagger UI | http://localhost:9940/swagger-ui | `local`, `h2-local` |
| H2 Console | http://localhost:9940/h2-console | `h2-local` |

### Application Profiles

| Profile | Database | Purpose |
|---|---|---|
| `local` (default) | PostgreSQL at `localhost:15439` | Local development |
| `h2-local` | H2 In-Memory | Local development without PostgreSQL |
| `dev` | PostgreSQL at `metamora-postgres:5432` | Development server (port `18080`) |
| `docker` | PostgreSQL configured through `DB_*` environment variables | Docker deployment |
| `h2-docker` | File-based H2 database at `/data` | Standalone Docker deployment |

---

## Docker Deployment

MetaMora provides Docker images for both PostgreSQL-backed and standalone H2 deployments.

The same Docker image supports HTTP and HTTPS environments.

When a request uses HTTPS, the session cookie automatically receives the `Secure` attribute.

If HTTPS is terminated by a reverse proxy such as Nginx, ensure that the proxy forwards the `X-Forwarded-Proto` header correctly.

> A separate `http-` image tag is no longer required.

### Available Docker Images

| Image | Dockerfile | Profile |
|---|---|---|
| `koboolean/metamora:latest` | `Dockerfile` | `docker` (PostgreSQL) |
| `koboolean/metamora:h2-latest` | `Dockerfile-h2` | `h2-docker` (file-based H2) |

Both images are built with a multi-stage Dockerfile from the repository root (`docker build -t koboolean/metamora:latest .`, `docker build -f Dockerfile-h2 -t koboolean/metamora:h2-latest .`):

1. `node` stage — `npm ci` and `npm run build` (React)
2. `eclipse-temurin:21-jdk` stage — Gradle builds `metamora.jar` with the React build included (`-PfrontendPrebuilt=true`)
3. `eclipse-temurin:21-jre` runtime — runs `java -jar /app/metamora.jar` (Node.js is not included)

A single container serves both the React UI and the REST API. Stop containers gracefully (`docker stop`) so the H2 file database is flushed; login sessions are kept in the database.

### PostgreSQL + Application (Docker Compose)

```bash
cd docker/metamora
docker-compose -p metamora up -d
```

### Docker Standalone Commands

Create a dedicated Docker network:

```bash
docker network create metamora-network
```

Start PostgreSQL 18:

```bash
docker run -d \
  --name metamora-postgres \
  --network metamora-network \
  -e POSTGRES_PASSWORD=metamora \
  -e POSTGRES_DB=metamora \
  -e POSTGRES_USER=metamora \
  -p 15439:5432 \
  -v ./volume/metamora:/var/lib/postgresql \
  postgres:18.6
```

> PostgreSQL 18 uses `/var/lib/postgresql` as the volume mount path in this Docker configuration.

Start the application:

```bash
docker run -d \
  --name metamora-app \
  --network metamora-network \
  -e DB_HOST=metamora-postgres \
  -p 9940:9940 \
  koboolean/metamora:latest
```

The PostgreSQL connection can be configured through the following environment variables:

- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USERNAME`
- `DB_PASSWORD`

### Standalone Deployment with H2

For a lightweight deployment without an external database:

```bash
docker run -d \
  --name metamora-app \
  -v ./volume/h2:/data \
  -p 9940:9940 \
  koboolean/metamora:h2-latest
```

This configuration uses a file-based H2 database to persist application data.

---

## Database Management

MetaMora uses **Flyway** for database schema versioning and initialization.

Migration scripts are located in:

```text
metamora-app/src/main/resources/db/migration/
├── postgresql/
└── h2/
```

- Pending Flyway migrations are automatically applied during application startup.
- Hibernate/JPA performs schema validation (`validate`) rather than automatically modifying database tables.
- Databases created before Flyway integration are automatically baselined to preserve existing data.
- Some migrations modify existing table structures.

> **Important:** Back up production databases before upgrading, especially when applying structural migrations such as V6, which removes the term-to-column join table, and V10, which drops the tables of the removed messenger and code rule features.
---

## User Accounts

### Default Administrator Account

| Property | Value |
|---|---|
| Username | `admin` |
| Password | `admin` |

For security reasons, the default administrator password must be changed during the first login.

The application displays a password change dialog before granting access.

The same process applies when an administrator resets a user's password.

### Authentication Workflow

1. Enter a valid username and password.
2. Complete the mandatory password change if required.
3. Select a project the user is authorized to access.
4. Access the application based on the assigned role and permissions.

Newly registered users receive the `NOT_APPROVE` role by default.

An administrator must assign an appropriate role before the user can access additional features.

> **Security Notice:** The default administrator credentials are intended for initial setup only. Change the password before exposing the application to other users or networks.

---

## Project Roadmap

MetaMora is evolving beyond rule-based naming and DDL generation toward a comprehensive data modeling and metadata management platform.

The following capabilities are planned and should not be considered implemented unless explicitly documented in a release.

### Data Modeling

- Logical and physical data model management
- Interactive entity-relationship diagrams (ERD)
- Database schema import and reverse engineering
- Synchronization between modeled structures and physical database schemas

### Metadata Management

- Centralized metadata catalog
- Table and column metadata discovery
- Schema versioning and change tracking
- Metadata relationships and dependency analysis

### Data Standardization

- Standard terminology and domain management
- Automated mapping between physical columns and standard terms
- Naming convention validation
- Data standard compliance analysis

### AI Metadata Intelligence

- AI-assisted column description generation
- Intelligent data dictionary generation
- Semantic analysis of database structures
- AI-assisted terminology mapping
- Human review and approval of AI-generated metadata

### Integration & Governance

- MCP Server integration for AI agents
- REST API integration with external developer tools
- Schema change impact analysis
- Metadata governance and audit capabilities

---

## From MetaGen to MetaMora

MetaMora is the successor to **MetaGen**, a developer productivity project focused on standardized naming conventions, data dictionaries, and automated development artifacts.

While MetaGen focused primarily on generating naming conventions and development documentation, MetaMora expands the scope to cover the entire metadata lifecycle.

The long-term goal is to provide a unified environment for:

**Modeling → Standardization → Metadata Management → AI Analysis → Governance**

MetaGen will remain available as a legacy project, while future development will focus on MetaMora.

---

## License

MetaMora is licensed under the [MIT License](LICENSE).

You are free to use, modify, and distribute the project, provided that the original copyright notice and license terms are preserved.

---

<p align="center">
  <strong>MetaMora</strong>
</p>

<p align="center">
  <em>Model. Standardize. Understand.</em>
</p>

<p align="center">
  Open Source Data Modeling & Metadata Management Platform
</p>
