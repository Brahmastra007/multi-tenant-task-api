# Implementation Plan: Multi-Tenant Task & Project Management API

---

## Suggested Improvements to the Spec

| # | Addition | Why |
|---|----------|-----|
| 1 | **Flyway for schema migrations** | Hibernate `ddl-auto: create` is dangerous in production. Flyway gives you versioned, reproducible SQL migrations and works perfectly with Testcontainers. |
| 2 | **Pagination on list endpoints** | A real API with thousands of tasks per org cannot return everything in one call. Spring Data's `Pageable` / `Page<T>` adds this almost for free. |
| 3 | **Audit fields on all entities** (`created_at`, `updated_at`, `created_by`) | Spring Data's `@EnableJpaAuditing` makes this trivial and it is expected on any production data model. |
| 4 | **Docker Compose for local dev** | Avoids every developer needing a manually configured Postgres instance. Also serves as documentation of the runtime environment. |
| 5 | **OpenAPI / Swagger UI via springdoc-openapi** | Self-documenting APIs are a standard. Annotations are minimal and the payoff (interactive docs, schema export) is high. |
| 6 | **Unit tests alongside integration tests** | The spec only calls for Testcontainers integration tests. The service layer should have fast, isolated unit tests with Mockito. Full integration tests are slow — save them for cross-cutting concerns (auth, tenant isolation). |
| 7 | **Refresh tokens** | A JWT without refresh tokens means the user logs out when the access token expires. Adding a short-lived access token + longer-lived refresh token is a standard pattern and demonstrates deeper security understanding. |
| 8 | **Configuration profiles** (`dev`, `test`, `prod`) | Externalised configuration per environment is a basic production requirement. `application-dev.yml` with a local DB, `application-test.yml` pointing to Testcontainers, `application-prod.yml` pulling from environment variables. |

---

## Phase 1 — Project Bootstrap & Infrastructure

**Goal:** Runnable Spring Boot app connected to a local PostgreSQL with Flyway managing the schema.

**Tasks:**
1. Generate project at start.spring.io with dependencies:
   - `spring-boot-starter-web`
   - `spring-boot-starter-data-jpa`
   - `spring-boot-starter-security`
   - `spring-boot-starter-validation`
   - `postgresql` driver
   - `lombok`
   - `mapstruct` + `mapstruct-processor`
   - `flyway-core`
   - `jjwt-api`, `jjwt-impl`, `jjwt-jackson`
   - `springdoc-openapi-starter-webmvc-ui`
   - `spring-boot-testcontainers`, `testcontainers-postgresql` (test scope)
2. Set up package structure:
   ```
   com.example.taskapi
   ├── config/          # Security, JPA auditing, OpenAPI beans
   ├── domain/          # JPA entities
   ├── dto/             # Request/Response records
   ├── exception/       # Custom exceptions + GlobalExceptionHandler
   ├── mapper/          # MapStruct interfaces
   ├── repository/      # Spring Data JPA interfaces
   ├── security/        # JWT utils, filters, TenantContext
   └── service/         # Business logic
   ```
3. Create `application.yml` with profiles (`dev`, `test`, `prod`).
4. Add `docker-compose.yml` with a Postgres service.
5. Add `V1__init_schema.sql` as the first Flyway migration (empty placeholder).

**Deliverable:** `./mvnw spring-boot:run` starts against a local DB with no errors.

---

## Phase 2 — Domain Model & Database Schema

**Goal:** All four entities mapped to the DB via Flyway migrations.

**Tasks:**
1. Write `V1__init_schema.sql` — all four tables:
   - `organizations(id UUID PK, name VARCHAR, created_at TIMESTAMP)`
   - `users(id UUID PK, org_id UUID FK, email VARCHAR UNIQUE, password_hash VARCHAR, role VARCHAR, created_at TIMESTAMP, updated_at TIMESTAMP)`
   - `projects(id UUID PK, org_id UUID FK, name VARCHAR, description TEXT, created_at TIMESTAMP, updated_at TIMESTAMP)`
   - `tasks(id UUID PK, org_id UUID FK, project_id UUID FK, assignee_id UUID FK nullable, title VARCHAR, description TEXT, status VARCHAR, created_at TIMESTAMP, updated_at TIMESTAMP)`
   - Add indexes on all `org_id` columns (critical for tenant query performance).
2. Write JPA entity classes (`@Entity`, `@Table`, `@Column`, lazy/eager decisions explicit).
3. Enable `@EnableJpaAuditing` in a config class; add `@CreatedDate`/`@LastModifiedDate`/`@CreatedBy` to a `BaseEntity` that all entities extend.
4. Write repository interfaces — every query method **must** include `organizationId` as a parameter. No queries that could return cross-tenant data.

**Deliverable:** Flyway runs on startup, all tables created with correct constraints and indexes.

---

## Phase 3 — Multi-Tenancy & Security Infrastructure

**Goal:** Every request carries a validated JWT; tenant context is set on the thread before any service code runs.

**Tasks:**
1. **`TenantContext`** — `ThreadLocal<UUID>` wrapper with `set`, `get`, `clear` statics. Must be cleared in a `finally` block to prevent context leakage across requests.
2. **`JwtTokenProvider`** — encapsulates `jjwt`: `generateAccessToken(user)`, `generateRefreshToken(user)`, `validateToken(token)`, `extractClaims(token)`. Claims include `sub` (user ID), `org` (organization ID), `role`.
3. **`JwtAuthenticationFilter`** (extends `OncePerRequestFilter`):
   - Extract `Authorization: Bearer <token>` header.
   - Validate token via `JwtTokenProvider`.
   - Populate `TenantContext` with org ID from claims.
   - Build `UsernamePasswordAuthenticationToken` and register in `SecurityContextHolder`.
   - Call `filterChain.doFilter` inside a `try/finally` that clears `TenantContext`.
4. **`SecurityFilterChain` bean**:
   - `sessionManagement` → `STATELESS`.
   - Permit: `POST /api/auth/**`.
   - Require authentication for everything else.
   - Role-based rules: `DELETE` on projects/tasks → `ADMIN` or `MANAGER`; user management → `ADMIN` only.
5. **`AuthController`** with `POST /api/auth/register` and `POST /api/auth/login` (returns access + refresh token pair), and `POST /api/auth/refresh`.

**Deliverable:** Unauthenticated requests to protected endpoints return 401; valid JWT grants access with correct tenant context.

---

## Phase 4 — Service Layer & Business Logic

**Goal:** Transactional services that enforce tenant isolation and RBAC business rules.

**Tasks:**
1. **`OrganizationService`** — `create(name)`, `findById(id)`.
2. **`UserService`** — `register(dto)` hashes password via `BCryptPasswordEncoder`, validates email uniqueness within the org. `loadUserByUsername` for Spring Security.
3. **`ProjectService`**:
   - `create(dto)` — sets `orgId` from `TenantContext`, never from the request body.
   - `findAll(pageable)` — scoped to `TenantContext.get()`.
   - `findById(id)` — verifies `project.orgId == TenantContext.get()` or throws `ResourceNotFoundException`.
   - `update(id, dto)`, `delete(id)` — same tenant check.
4. **`TaskService`**:
   - All operations read `orgId` from `TenantContext`.
   - `create(dto)` — validates that `projectId` and `assigneeId` (if provided) belong to the **same org**. This is the cross-tenant prevention.
   - `findAllByProject(projectId, pageable)` — first confirms the project belongs to the current tenant.
   - `updateStatus(id, status)`.
   - `delete(id)`.

**Key invariant:** No service method ever trusts an `orgId` from a request body — it always comes from `TenantContext`.

**Deliverable:** Service layer unit tests (Mockito) pass, including the cross-tenant rejection scenarios.

---

## Phase 5 — API Controllers & DTOs

**Goal:** Clean REST endpoints with validated request DTOs and structured response DTOs.

**Tasks:**
1. **DTOs as Java records** (immutable, clean):
   - `RegisterRequest`, `LoginRequest`, `AuthResponse` (contains tokens)
   - `CreateProjectRequest` / `ProjectResponse`
   - `CreateTaskRequest` / `UpdateTaskRequest` / `TaskResponse`
   - `PaginatedResponse<T>` — wrapper for paginated list responses
2. **MapStruct mappers** — one interface per domain area (`ProjectMapper`, `TaskMapper`, `UserMapper`). No manual field-by-field mapping in service/controller code.
3. **Controllers**:
   - `AuthController` — `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh`
   - `ProjectController` — `GET/POST /api/projects`, `GET/PUT/DELETE /api/projects/{id}`
   - `TaskController` — `GET/POST /api/projects/{projectId}/tasks`, `GET/PUT/DELETE /api/tasks/{id}`
   - `UserController` (admin-only) — `GET /api/users`, `DELETE /api/users/{id}`
4. Add `@Valid` on all `@RequestBody` parameters; use Bean Validation annotations on DTOs (`@NotBlank`, `@Email`, `@Size`, etc.).

**Deliverable:** All endpoints reachable, Swagger UI at `/swagger-ui.html` shows full API spec.

---

## Phase 6 — Global Exception Handling

**Goal:** Every error returns the same structured JSON envelope; no stack traces leak to clients.

**Tasks:**
1. Define custom exceptions:
   - `ResourceNotFoundException` (404)
   - `AccessDeniedException` (403) — for cross-tenant access attempts
   - `DuplicateResourceException` (409) — e.g., duplicate email
   - `InvalidTokenException` (401)
2. Define standard error response DTO:
   ```json
   {
     "timestamp": "2026-10-04T12:00:00Z",
     "status": 404,
     "error": "Not Found",
     "message": "Project with id [x] not found",
     "path": "/api/projects/x"
   }
   ```
3. `GlobalExceptionHandler` (`@RestControllerAdvice`) — one `@ExceptionHandler` per custom exception, plus a catch-all for `Exception.class` that returns 500 without leaking details.
4. Handle `MethodArgumentNotValidException` to return field-level validation errors.

**Deliverable:** Any invalid request or access violation returns a parseable JSON error with no raw exception output.

---

## Phase 7 — Integration Tests with Testcontainers

**Goal:** Full integration test suite that runs against a real PostgreSQL container, validating auth flows and tenant isolation.

**Tasks:**
1. Create `AbstractIntegrationTest` base class annotating with `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Testcontainers`. Spin up a `PostgreSQLContainer` as a `@Container` static field; override datasource properties via `@DynamicPropertySource`.
2. Test cases to cover:
   - **Auth flow:** Register org + user → login → receive JWT → use JWT on protected endpoint.
   - **Project CRUD:** Create, list (paginated), update, delete — all scoped to correct org.
   - **Task CRUD:** Create under a project, update status, delete.
   - **Cross-tenant isolation:** User from Org A cannot read/modify Org B's projects/tasks — returns 403 or 404.
   - **RBAC enforcement:** `USER` role cannot delete a project (403); `ADMIN` can.
   - **Cross-tenant task assignment prevention:** Assigning a task to a user from a different org returns 400.
3. Use `TestRestTemplate` or `MockMvc` for HTTP-level assertions.

**Deliverable:** `./mvnw verify` runs all tests green with a real Postgres container.

---

## Phase 8 — Polish & Documentation

**Goal:** Project is presentable, runnable from a README, and properly containerized.

**Tasks:**
1. **`README.md`** with: architecture diagram, how to run locally (`docker-compose up`, then `mvnw spring-boot:run`), how to run tests, API overview table.
2. **Swagger annotations** on controllers (`@Operation`, `@ApiResponse`, `@Tag`) for clean OpenAPI output.
3. **`Dockerfile`** — multi-stage build: compile with `maven:3.9-eclipse-temurin-17`, run with `eclipse-temurin:17-jre-alpine`.
4. **Extend `docker-compose.yml`** to include the app service so the whole stack starts with one command.
5. Final review: confirm no `org_id` can ever come from a request body in any service method; confirm `TenantContext` is always cleared in `finally`.

---

## Phase Dependency Summary

```
Phase 1 (Bootstrap)
    └─► Phase 2 (Domain Model)
            └─► Phase 3 (Security/JWT)
            └─► Phase 4 (Service Layer)  ◄── depends on Phase 3 for TenantContext
                    └─► Phase 5 (Controllers/DTOs)
                            └─► Phase 6 (Exception Handling)
                                    └─► Phase 7 (Integration Tests)
                                            └─► Phase 8 (Polish)
```

Phases 3 and 4 have a partial overlap — `TenantContext` and basic security config (Phase 3) must exist before services use them (Phase 4), but auth endpoints themselves can be fleshed out after the service layer is stable.
