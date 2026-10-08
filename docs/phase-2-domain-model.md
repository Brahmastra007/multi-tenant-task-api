# Phase 2 — Domain Model & Database Schema

**Goal:** All four entities mapped to the database through Flyway migrations, with tenant isolation built into both the repositories and the schema.

**Result:** Flyway creates the tables on startup, Hibernate's `validate` confirms every entity matches its table, and `./mvnw test` passes 6 tests (smoke test + 5 tenant-isolation tests) against real PostgreSQL 18.

> **Numbering note:** the spec calls "Tenant Context & Security" Phase 2. We follow `implementation_plan.md`, where the domain model comes first, because security needs users in the database.

---

## 1. Files added or changed

| File | Purpose |
|---|---|
| `db/migration/V1__init_schema.sql` | The real schema (replaces the Phase 1 placeholder). |
| `domain/BaseEntity.java` | `id` + audit fields shared by every entity. |
| `domain/TenantScopedEntity.java` | Adds `organizationId` (`org_id`) for tenant-owned entities. |
| `domain/Organization.java`, `User.java`, `Project.java`, `Task.java` | The entities. |
| `domain/Role.java`, `TaskStatus.java` | Enums matching the SQL `CHECK` constraints. |
| `config/JpaAuditingConfig.java` | `@EnableJpaAuditing` + the `AuditorAware` bean. |
| `repository/TenantScopedRepository.java` | Shared org-scoped repository methods. |
| `repository/OrganizationRepository.java`, `UserRepository.java`, `ProjectRepository.java`, `TaskRepository.java` | One repository per entity. |
| `test/.../repository/TenantIsolationRepositoryTest.java` | Proves scoping, auditing and the cross-tenant foreign keys. |
| `test/.../TestcontainersConfiguration.java` | Made `public` so tests in sub-packages can `@Import` it. |

---

## 2. Database schema (`V1__init_schema.sql`)

```
organizations ─┬─< users ─────────┐ (assignee, optional)
               ├─< projects ─< tasks
               └──────────────────┘ (org_id on every tenant table)
```

| Decision | Why |
|---|---|
| `UUID` primary keys | IDs can't be guessed or counted (`/projects/1`, `/projects/2`, ...). |
| `TIMESTAMPTZ` columns, mapped to `Instant` | A fixed moment in UTC. Plain `TIMESTAMP` has no time zone and breaks when servers run in different zones. |
| `role` / `status` as `VARCHAR` + `CHECK` | Readable, easy to extend, and the database rejects bad values. Native Postgres `ENUM`s are awkward to change. |
| Audit columns on **all four** tables | One `BaseEntity` for everything. The plan gave `organizations` only `created_at`. |
| `created_by` nullable, no foreign key | Informational only. At registration nobody is logged in yet. |
| `email` **globally** unique | Login works with email + password alone. See section 6. |
| `UNIQUE (id, org_id)` on `projects` and `users` + **composite foreign keys** from `tasks` | ⭐ The database itself guarantees a task, its project and its assignee all belong to the same organization (section 2.1). |
| `ON DELETE CASCADE` on the task → project FK | Deleting a project deletes its tasks. |
| `ON DELETE SET NULL (assignee_id)` on the task → assignee FK | Deleting a user unassigns their tasks. The column list (Postgres 15+) clears only `assignee_id`, not `org_id`. |
| Index on every `org_id`, plus `tasks.project_id` / `tasks.assignee_id` | Every query filters by tenant. Postgres does **not** index foreign key columns automatically. |

Task statuses `TODO`, `IN_PROGRESS` and `DONE` are our own choice. The spec doesn't list them.

### 2.1 Composite foreign keys: defence in depth

A normal FK `tasks.project_id → projects.id` only checks that *some* project exists, so it would allow an Org B task to point at Org A's project. Instead:

```sql
-- projects: CONSTRAINT uq_projects_id_org UNIQUE (id, org_id)
CONSTRAINT fk_tasks_project FOREIGN KEY (project_id, org_id) REFERENCES projects (id, org_id)
```

The pair `(project_id, org_id)` must match a real project **in the same organization**. Phase 4's service checks are the first line of defence. This constraint catches any bug that gets past them. A foreign key only checks a pair when every column in it is non-NULL (`MATCH SIMPLE`), so an unassigned task (`assignee_id IS NULL`) passes.

---

## 3. Entities

```
BaseEntity               @MappedSuperclass: id, createdAt, updatedAt, createdBy
 ├── Organization        the tenant itself
 └── TenantScopedEntity  @MappedSuperclass: + organizationId (org_id, updatable = false)
      ├── User
      ├── Project
      └── Task           @ManyToOne(LAZY) project, @ManyToOne(LAZY) assignee
```

### 3.1 `@MappedSuperclass`

It means "not a table. Copy my fields into each child entity's table." Hibernate ignores fields inherited from an unannotated parent, so without it `id` and the audit fields would not be mapped. There is no `base_entity` table, and you can't query `BaseEntity` directly. That's the difference from `@Entity` + `@Inheritance`, which is meant for real "is-a" hierarchies you query as a group. Annotations such as `@EntityListeners` are inherited too.

### 3.2 Decisions

| Decision | Why |
|---|---|
| `@UuidGenerator(style = VERSION_7)` | UUIDv7 starts with a timestamp, so new rows go at the end of the primary-key index. Random UUIDv4 values scatter inserts across the index and slow it down as it grows. |
| `organizationId` is a plain `UUID`, not `@ManyToOne Organization` | We only **filter** by it. A service can set it from the tenant context without loading the organization row. The SQL foreign key still guarantees integrity. |
| `updatable = false` on `org_id`, `created_at`, `created_by` | Hibernate leaves them out of `UPDATE`, so a row can never move to another tenant. |
| Audit fields have getters only | Only Spring Data writes them. |
| `@Enumerated(EnumType.STRING)` | Stores `"ADMIN"`. The default `ORDINAL` stores `0`, which silently changes meaning if someone reorders the enum. |
| `fetch = FetchType.LAZY` on every `@ManyToOne` | JPA defaults to EAGER, which loads the project and assignee with every task (the N+1 query problem). |
| No `@OneToMany List<Task>` on `Project` | It would load every task with no paging. Task lists come from a paginated repository query instead. |
| No Lombok `@Data` / no `equals`/`hashCode` override | `@Data` builds `equals`/`hashCode` from every field, which touches lazy relations and changes once the id is assigned. Java's default identity-based `equals` is safe. |
| Table `users`, not `user` | `USER` is a reserved word in PostgreSQL. |
| `passwordHash` field name | Makes it obvious that only a BCrypt hash is stored. |

### 3.3 Auditing (`JpaAuditingConfig`)

`@CreatedDate` / `@LastModifiedDate` / `@CreatedBy` are only markers. `@EnableJpaAuditing` turns on the `AuditingEntityListener` that fills them in before each insert or update. `AuditorAware<UUID>` answers "who is the current user?" and returns `Optional.empty()` for now, so `created_by` stays `NULL` until Phase 3 reads the user ID from the JWT.

`@EnableJpaAuditing` sits in its own `@Configuration` class, not on `TaskApiApplication`, so web-only test slices (`@WebMvcTest`) don't fail looking for JPA.

---

## 4. Repositories: unscoped queries can't be written

`JpaRepository` brings `findById` and `findAll`, which **don't filter by organization**. Calling `projectRepository.findById(id)` would return another tenant's data. To avoid that, our repositories extend Spring Data's empty marker interface `Repository<T, ID>` and declare **only** scoped methods. Spring Data recognises `save` and `delete` and links them to its built-in implementation. Every other method is a **derived query** built from its name:

```java
Optional<Project> findByIdAndOrganizationId(UUID id, UUID organizationId);
// → select ... from projects where id = ? and org_id = ?
```

A typo in a derived method name makes the app **fail at startup**, not at runtime.

| Repository | Methods |
|---|---|
| `TenantScopedRepository<T extends TenantScopedEntity>` (`@NoRepositoryBean`, so Spring doesn't create a bean for it) | `save`, `delete`, `findByIdAndOrganizationId`, `existsByIdAndOrganizationId`, `findAllByOrganizationId(orgId, Pageable)` |
| `UserRepository` | + `findByEmail`, `existsByEmail`: **deliberately unscoped**, for login and registration only, when the organization isn't known yet. Safe because email is globally unique. |
| `ProjectRepository` | (inherited only) |
| `TaskRepository` | + `findAllByProjectIdAndOrganizationId(projectId, orgId, Pageable)` |
| `OrganizationRepository` | `save`, `findById`. No `findAll`, since listing every tenant is never valid. |

`Pageable` carries page, size and sort. `Page<T>` returns the rows plus total counts. Spring adds `LIMIT/OFFSET` and a `COUNT` query.

---

## 5. Tests (`TenantIsolationRepositoryTest`)

`@DataJpaTest` starts only the JPA layer (entities, repositories, Flyway, Hibernate) and runs each test in a transaction that is **rolled back**. Slices ignore our own `@Configuration` classes, so the test `@Import`s `TestcontainersConfiguration` and `JpaAuditingConfig`.

| Test | Proves |
|---|---|
| `scopedFinderHidesOtherTenantsProject` | Org B can't find or "exists-check" Org A's project. |
| `paginatedListReturnsOnlyOwnTenantsRows` | Paged lists contain only the caller's org. |
| `auditingFillsTimestamps` | `created_at` / `updated_at` are filled automatically. |
| `databaseRejectsTaskInOtherTenantsProject` | `fk_tasks_project` blocks a cross-tenant project. |
| `databaseRejectsTaskAssignedToOtherTenantsUser` | `fk_tasks_assignee` blocks a cross-tenant assignee. |

`TestEntityManager.flush()` forces Hibernate to send the `INSERT` immediately. Without it, Hibernate waits for a commit, the test transaction rolls back instead, and the constraint is never checked.

---

## 6. Where we changed the plan

| Plan said | We did | Why |
|---|---|---|
| `organizations` has only `created_at` | All tables have `created_at`, `updated_at`, `created_by` | One shared `BaseEntity`. Matches plan improvement #3. |
| Email `UNIQUE` (schema) vs "unique within the org" (Phase 4) | Globally unique | The plan contradicts itself. Global uniqueness lets login work with email + password alone. |
| `TIMESTAMP` | `TIMESTAMPTZ` | Time-zone-safe. |
| Simple foreign keys | Composite `(id, org_id)` foreign keys | The database also enforces cross-tenant prevention. |
| Repositories with `organizationId` parameters | Also removed every unscoped inherited method | The rule can't be broken by accident. |
| Integration tests in Phase 7 | One repository test now | Proves the Phase 2 deliverable. The full HTTP-level suite stays in Phase 7. |

---

## 7. Verification

```bash
docker compose down -v        # V1 changed: drop the old local database (it has the placeholder V1's checksum)
docker compose up -d
./mvnw spring-boot:run        # look for: Successfully applied 1 migration ... now at version v1
./mvnw test                   # 6 tests, 0 failures
```

Inspect the schema in the running container:

```bash
docker exec -it taskapi-postgres psql -U taskapi -d taskapi -c '\d tasks'
```

---

## 8. Next: Phase 3 (multi-tenancy & security)

1. `TenantContext` (`ThreadLocal<UUID>`), cleared in a `finally` block.
2. `JwtTokenProvider` and `JwtAuthenticationFilter`.
3. Change `AuditorAware` to return the authenticated user's ID.
4. `SecurityFilterChain`: stateless, `/api/auth/**` public, role rules.
