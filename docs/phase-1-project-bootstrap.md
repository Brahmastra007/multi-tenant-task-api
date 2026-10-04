# Phase 1 — Project Bootstrap & Infrastructure

**Goal:** A Spring Boot app that starts, connects to a local PostgreSQL database, and lets Flyway manage the database schema.

**Result:** `./mvnw spring-boot:run` starts in about 2 seconds with no errors, and `./mvnw test` passes against a temporary PostgreSQL container.

---

## 1. Environment & version decisions

| Tool | Version | Why |
|---|---|---|
| Java (JDK) | **Temurin 25 LTS** | LTS (Long-Term Support) release, so it gets updates for years. Spring Boot 4 recommends it. The machine already had JDK 26, but that's a short-lived (non-LTS) release, and tools that hook into compilation or bytecode (Lombok, Mockito, Hibernate) often don't support brand-new JDKs yet. |
| Spring Boot | **4.1.1** | Newest stable release. Spring Boot 3.x is no longer offered on start.spring.io. |
| Maven | 3.9.16 (via the wrapper) | Fixed by the Maven Wrapper, so everyone builds with the same version. |
| PostgreSQL | **18** (`postgres:18-alpine`) | Newest major version. The same image is used for local development and for tests. |

### Making Maven use JDK 25

macOS uses the newest installed JDK (26) by default. Maven and `./mvnw` decide which JDK to use from the `JAVA_HOME` environment variable instead, so `~/.zshrc` sets it:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"
```

`/usr/libexec/java_home -v 25` is a macOS tool that prints where JDK 25 is installed, so no path is hard-coded. To switch JDKs later, change the number.

---

## 2. Spring Initializr settings (start.spring.io)

- **Project:** Maven · **Language:** Java · **Spring Boot:** 4.1.1 · **Packaging:** Jar · **Configuration:** YAML · **Java:** 25
- **Group:** `com.example` · **Artifact:** `task-api` · **Package:** `com.example.taskapi`
- **Dependencies:** Spring Web, Spring Data JPA, PostgreSQL Driver, Flyway Migration, Spring Security, Validation, Lombok, Testcontainers

### What Initializr generated

| File | Purpose |
|---|---|
| `pom.xml` | Maven build file: project identity, Java version, dependencies, plugins. |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` | Maven Wrapper. Always run `./mvnw`, not `mvn`. |
| `TaskApiApplication.java` | Entry point. `@SpringBootApplication` turns on component scanning, auto-configuration and configuration support. |
| `application.yaml` | Application settings. |
| `TestcontainersConfiguration.java` | Starts a Postgres container for tests. `@ServiceConnection` passes its URL and credentials to Spring automatically. |
| `TaskApiApplicationTests.java` | Smoke test: passes if the whole app can start. |
| `TestTaskApiApplication.java` | Runs the app locally with a temporary Postgres container, so no local database is needed. |

### How Spring Boot 4 differs from older tutorials

- `spring-boot-starter-web` is now **`spring-boot-starter-webmvc`**.
- Flyway has its own starter: **`spring-boot-starter-flyway`**.
- Each starter has a matching **`-test`** starter, instead of one big `spring-boot-starter-test`.
- Testcontainers 2.x renamed its modules (`testcontainers-postgresql`), and the class moved to `org.testcontainers.postgresql.PostgreSQLContainer`.
- Spring Boot uses **Jackson 3** (package `tools.jackson`) instead of Jackson 2 (`com.fasterxml.jackson`).

---

## 3. Changes made

### 3.1 `pom.xml`: libraries not offered by Initializr

| Library | Version | Scope | Why |
|---|---|---|---|
| `mapstruct` | 1.6.3 | compile | `@Mapper` annotations for converting between entities and DTOs. 1.6.3 is the newest *stable* version (1.7 is still a beta). |
| `jjwt-api` | 0.13.0 | compile | The JWT interfaces our code calls. |
| `jjwt-impl` | 0.13.0 | runtime | The JWT implementation. Runtime scope means our code can't import its internal classes by accident. |
| `jjwt-jackson` | 0.13.0 | runtime | Converts JWT contents to and from JSON. It uses Jackson 2, which can sit next to Spring's Jackson 3 because the package names differ. Spring Boot's dependency management upgrades it to 2.21.x. |
| `springdoc-openapi-starter-webmvc-ui` | 3.1.1 | compile | Generates the OpenAPI spec and Swagger UI. Version 3.1.x is built for Spring Boot 4.1. |

All third-party versions live in `<properties>`, so each one is set in one place. Spring's own libraries don't need versions because the `spring-boot-starter-parent` POM manages them.

**Annotation processors** (tools that run during compilation and generate code) are listed once, in plugin-level `<configuration>` of `maven-compiler-plugin`, so they apply to both main and test code. **The order matters:**

1. `lombok`: generates getters, setters and constructors.
2. `lombok-mapstruct-binding` (0.2.0): makes MapStruct wait until Lombok is done.
3. `mapstruct-processor`: generates mapper classes that *call* the methods Lombok generated.

Without the binding, MapStruct can fail with "unknown property" errors.

**Cleanup:** filled in `<name>` and `<description>`, and removed the empty `<licenses>`, `<developers>` and `<scm>` blocks. Those are only used when publishing libraries.

### 3.2 Package structure

All packages sit **below** `com.example.taskapi`, because `@SpringBootApplication` only scans its own package and the ones below it. A class outside that tree is silently ignored.

```
com.example.taskapi
├── config/       Spring configuration: security, JPA auditing, OpenAPI
├── domain/       JPA entities
├── dto/          Request/response records
├── exception/    Custom exceptions + global exception handler
├── mapper/       MapStruct interfaces
├── repository/   Spring Data JPA repositories (always scoped by organization)
├── security/     JWT handling, auth filter, TenantContext
└── service/      Transactional business logic
```

Each package has a `package-info.java`, the standard Java file for describing a package. It also keeps the folder in Git, which doesn't track empty folders.

### 3.3 `docker-compose.yml`: local PostgreSQL

- **Pinned image** `postgres:18-alpine`. `latest` can jump to a new major version without warning. Pinning to `18` still picks up bug-fix releases.
- **Named volume** `postgres-data` mounted at `/var/lib/postgresql`. Postgres 18 images store data in a version-specific subfolder, so the old `/var/lib/postgresql/data` mount from most tutorials is wrong for version 18.
- **Healthcheck** with `pg_isready`, so Docker reports when Postgres can actually accept connections.
- **Port** `5432:5432` maps your machine's port 5432 to the container's.

### 3.4 `TestcontainersConfiguration.java`: same database version in tests

Changed `postgres:latest` to `postgres:18-alpine`, so tests run on exactly the same database version as development.

### 3.5 Configuration profiles

Spring always loads `application.yaml` first, then `application-<profile>.yaml` for each active profile. Values in the profile file override the base file. You choose a profile with `SPRING_PROFILES_ACTIVE`.

**`application.yaml`** (shared settings):

| Setting | Why |
|---|---|
| `spring.profiles.default: dev` | If no profile is chosen, use `dev`, so `./mvnw spring-boot:run` works with no extra setup. |
| `spring.jpa.hibernate.ddl-auto: validate` | Hibernate never creates or changes tables, because **Flyway owns the schema**. Hibernate only checks that the entities match the tables, and refuses to start if they don't. |
| `spring.jpa.open-in-view: false` | Stops Spring from keeping a database connection open for the whole HTTP request. Database access then happens only in the service layer, and hidden lazy-loading queries can't sneak in. |
| `spring.flyway.locations` | Where migration files live. This is the default value, written out so it's visible. |

**`application-dev.yaml`**: connects to the docker-compose database at `localhost:5432`, with `show-sql` and `format_sql` on so you can see what SQL Hibernate runs. `${DB_USERNAME:taskapi}` means "use the environment variable if it's set, otherwise `taskapi`."

**`application-prod.yaml`**: `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` must come from environment variables, with **no defaults**. If one is missing, the app fails at startup instead of quietly connecting to the wrong place. Real secrets are never committed.

### 3.6 `db/migration/V1__init_schema.sql`: first Flyway migration (placeholder)

Flyway runs the files named `V<n>__<description>.sql` (two underscores), in number order, and records each one in the `flyway_schema_history` table.

> **Rule:** never edit a migration after it has run. Flyway saves a checksum (fingerprint) of each migration and refuses to start if a file changes later. Changes go in a new file (`V2__...`).

The plan fills in V1 during Phase 2. That's acceptable only because the local database is disposable: reset it first with `docker compose down -v`.

### 3.7 `.gitignore`

Added `.DS_Store`, the macOS Finder metadata file. Ignored paths now include `target/` (build output), `HELP.md`, `.vscode/` (personal editor settings) and `.DS_Store`.

---

## 4. Where we changed the plan

| Plan said | We did | Why |
|---|---|---|
| Spring Boot 3.x, Java 17 | Spring Boot 4.1.1, Java 25 | Boot 3.x is no longer offered. Java 25 is the current LTS. |
| `application-test.yml` | Not created yet | `@ServiceConnection` already gives tests the container's database settings, and those take priority over `spring.datasource.*`. A test profile would have nothing to set yet. We'll add one in Phase 7 if needed. |
| `@DynamicPropertySource` for Testcontainers (Phase 7) | `@ServiceConnection` | The newer, simpler Spring Boot way to do the same thing. |
| `postgres:latest` (generated) | `postgres:18-alpine` | Reproducible and matches local development. |

---

## 5. Verification

```bash
docker compose up -d          # start Postgres (wait until "healthy")
./mvnw spring-boot:run        # start the app
./mvnw test                   # run tests (needs Docker running)
```

Startup log, and what each line means:

| Log line | Meaning |
|---|---|
| `falling back to 1 default profile: "dev"` | The `dev` default profile was applied. |
| `Database: jdbc:postgresql://localhost:5432/taskapi (PostgreSQL 18.6)` | Connected to the docker-compose database. |
| `Successfully applied 1 migration ... now at version v1` | Flyway ran V1. |
| `Using generated security password: ...` | Default Spring Security setup: every URL is locked. This goes away once JWT security is configured. |
| `Tomcat started on port 8080` | The web server is listening. |

`GET /` and `GET /swagger-ui.html` both return **401 Unauthorized**, as expected under the default security. Swagger and the auth endpoints get opened up during the security phase.

During `./mvnw test`, Testcontainers starts `postgres:18-alpine` on a **random port**, so it never clashes with local 5432. It also starts **Ryuk**, a helper container that deletes the test containers afterwards.

### Harmless warnings

- `sun.misc.Unsafe::objectFieldOffset has been called by lombok.permit.Permit`: Lombok uses an internal JDK feature that's being phased out. It isn't caused by our code.
- `SpringDoc /v3/api-docs endpoint is enabled by default`: a reminder that API docs are public. They can be turned off in the `prod` profile later.

---

## 6. Useful commands

| Command | What it does |
|---|---|
| `docker compose up -d` | Start Postgres in the background. |
| `docker compose stop` | Stop Postgres and keep its data. |
| `docker compose down -v` | Remove the container **and its data volume** (fresh database). |
| `./mvnw spring-boot:run` | Run the app (`dev` profile by default). |
| `./mvnw test` | Run the tests. |
| `./mvnw dependency:tree` | Show every dependency and where it comes from. |

---

## 7. Next: Phase 2 (domain model)

1. Run `docker compose down -v` to reset the local database, since V1 is about to change.
2. Write the real `V1__init_schema.sql`: `organizations`, `users`, `projects` and `tasks`, plus indexes on every `org_id`.
3. Create the JPA entities and a shared base class for audit fields, and turn on `@EnableJpaAuditing`.
4. Create repositories whose query methods always take `organizationId`.
