# Phase 3 — Multi-Tenancy & Security Infrastructure

**Goal:** Every request carries a validated JWT, and the tenant context is set on the thread before any controller or service code runs.

> Unfamiliar annotation or class? See [`glossary.md`](glossary.md).

**Result:** Requests without a valid token get **401**, and requests with the wrong role get **403**. A valid access token authenticates the user and sets `TenantContext` to their organization, which is cleared after every request. `./mvnw test` passes 26 tests (8 JWT unit tests + 12 HTTP-level security tests + the 6 from earlier phases).

---

## 1. Files added or changed

| File | Purpose |
|---|---|
| `security/TenantContext.java` | `ThreadLocal<UUID>` holding the current request's organization. `get()` throws if it isn't set. |
| `security/JwtProperties.java` | `app.jwt.*` settings (secret, token lifetimes), validated at startup. |
| `security/UserPrincipal.java` | The authenticated user (id, org, email, role) as a `UserDetails`. |
| `security/JwtTokenProvider.java` | Creates and verifies access and refresh tokens. |
| `security/JwtAuthenticationFilter.java` | Reads `Authorization: Bearer`, sets `SecurityContextHolder` and `TenantContext`, clears `TenantContext` in `finally`. |
| `security/DatabaseUserDetailsService.java` | Loads a user by email for login. |
| `config/SecurityConfig.java` | `SecurityFilterChain` (stateless, rules), `PasswordEncoder`, `AuthenticationManager`. |
| `config/JpaAuditingConfig.java` | `AuditorAware` now returns the logged-in user's id for `created_by`. |
| `controller/AuthController.java` | `POST /api/auth/register`, `/login`, `/refresh`. New `controller` package. |
| `service/AuthService.java` | Registration, login and refresh logic. |
| `dto/RegisterRequest`, `LoginRequest`, `RefreshRequest`, `AuthResponse` | Request and response records with validation. |
| `exception/InvalidTokenException.java` | Bad, expired or wrong-type token (401). |
| `exception/DuplicateResourceException.java` | Email already registered (409). |
| `TaskApiApplication.java` | `@ConfigurationPropertiesScan` so `JwtProperties` is found. |
| `application*.yaml` | Token lifetimes (shared), dev-only secret, `${JWT_SECRET}` in prod. |

---

## 2. Request flow

```
Request ─► JwtAuthenticationFilter ─► AuthorizationFilter (rules) ─► Controller ─► Service
            │ "Authorization: Bearer <jwt>"
            │ JwtTokenProvider.parseAccessToken (signature, expiry, type)
            │ SecurityContextHolder ← UserPrincipal + ROLE_x
            │ TenantContext         ← org id
            └ finally: TenantContext.clear()
```

A **missing or invalid token is not rejected by the filter**. The request just stays anonymous, and the rules then decide: public endpoints still work, and protected ones answer 401. This way a client that sends an expired token to `/api/auth/login` can still log in.

### 2.1 `TenantContext` and `ThreadLocal`

Tomcat handles each request on one thread, so a `ThreadLocal` works as per-request storage. Threads are **reused** across requests, so a value that isn't cleared leaks into the next request, meaning another user would run as the previous user's organization. That's why the filter clears it in `finally`, which runs even if the request throws. `get()` throws instead of returning `null`, so a missing tenant can never reach a query unnoticed.

Limitation: `ThreadLocal` values don't carry over to other threads (`@Async`, thread pools). We don't use those.

`SecurityContextHolder` is also stored per thread, but Spring Security clears it itself.

---

## 3. Tokens

### 3.1 What a JWT is

`header.payload.signature`, each part Base64url-encoded. The payload is **readable by anyone** (encoded, not encrypted), so it never holds secrets. The signature is HMAC-SHA256 over the header and payload using our secret key: changing one character makes verification fail.

| Claim | Value |
|---|---|
| `sub` | User id |
| `org` | Organization id |
| `email`, `role` | For `UserPrincipal` and `hasRole` |
| `type` | `ACCESS` or `REFRESH` |
| `iat`, `exp` | Issued at / expires at |

### 3.2 Access vs refresh tokens

| | Access token | Refresh token |
|---|---|---|
| Purpose | Authenticates every API call | Gets a new token pair |
| Sent to | Every endpoint, as `Authorization: Bearer` | Only `POST /api/auth/refresh`, in the body |
| Checked by | Signature + expiry, **no database query** | Signature + expiry + **user re-loaded from the database** |
| Lifetime | 15 minutes | 7 days |

Stateless JWTs **can't be revoked before they expire**, so a short access token lifetime limits how long a stolen token can be used. The refresh token lasts longer because it travels rarely, is checked against the database every time (a deleted user or changed role takes effect), and the `type` claim stops it from being used as an access token.

**Limitation:** refresh tokens aren't stored, so they can't be revoked either, and an old one stays valid until it expires even after a refresh. A possible extension is a `refresh_tokens` table with one-time use and reuse detection.

### 3.3 Safety details in `JwtTokenProvider`

| Detail | Why |
|---|---|
| `Keys.hmacShaKeyFor` | Fails at **startup** if the secret is under 256 bits (the HS256 minimum). |
| `verifyWith(key)` + `parseSignedClaims` | Rejects unsigned (`"alg": "none"`) tokens, a classic JWT attack. |
| `type` claim check | A refresh token can't be used as an access token, or the other way round. |
| One `parse...` method instead of `validateToken` + `extractClaims` | jjwt validates *while* parsing; two calls would parse twice. |
| Takes `UserPrincipal`, not the `User` entity | Independent of JPA and unit-testable without a database. |

### 3.4 Secret configuration

| Profile | Secret |
|---|---|
| `dev` (also used by tests) | Committed dev-only key, overridable with `JWT_SECRET` |
| `prod` | `${JWT_SECRET}`, no default: the app won't start without it |

Generate one with `openssl rand -base64 32`.

---

## 4. `SecurityConfig`

| Setting | Why |
|---|---|
| `csrf.disable()` | CSRF abuses cookies the browser sends automatically. A Bearer header is never sent automatically. |
| `STATELESS` sessions | No `JSESSIONID` cookie and no server-side session. |
| `HttpStatusEntryPoint(401)` | "Not logged in" gives **401**. The default for this setup would be 403. |
| `POST /api/auth/**` → `permitAll` | You can't need a token to get one. |
| `/error` → `permitAll` | Spring Boot forwards errors to `/error`. If it were locked, every error response (such as 409) would become 401. |
| Swagger paths → `permitAll` | API docs are viewable. |
| `DELETE /api/projects/**`, `/api/tasks/**` → `ADMIN` or `MANAGER` | Plan's role rule. |
| `/api/users/**` → `ADMIN` | User management. |
| `anyRequest().authenticated()` | **Deny by default**: new endpoints are protected automatically. |

Rules are checked top to bottom and the first match wins. `hasRole("ADMIN")` checks for the authority `ROLE_ADMIN`, which `UserPrincipal.getAuthorities()` provides.

**401 vs 403:** 401 = "I don't know who you are". 403 = "I know who you are, but you're not allowed".

The filter is created with `new` inside `SecurityConfig`, **not** as a `@Component`, because Spring Boot would also register a `@Component` filter as a plain servlet filter, outside the security chain, and it would run twice.

### 4.1 Login: `AuthenticationManager` + `DaoAuthenticationProvider`

`DaoAuthenticationProvider` calls `DatabaseUserDetailsService.loadUserByUsername(email)` and compares the BCrypt hash. If the email doesn't exist, it still runs a fake hash comparison and gives the same "Bad credentials" error, so response time and message don't reveal which emails exist. A failure throws `BadCredentialsException`, which Spring Security turns into 401.

Defining a `UserDetailsService` bean also removes Spring Boot's default user and the `Using generated security password` log line.

BCrypt hashes look like `$2a$10$...`: version `2a`, cost factor 10 (2¹⁰ rounds), then the random salt and the hash.

---

## 5. Auth endpoints

| Endpoint | Body | Success | Errors |
|---|---|---|---|
| `POST /api/auth/register` | `organizationName`, `email`, `password` | **201** + tokens | 400 invalid, 409 email taken |
| `POST /api/auth/login` | `email`, `password` | **200** + tokens | 400 invalid, 401 bad credentials |
| `POST /api/auth/refresh` | `refreshToken` | **200** + new tokens | 400 invalid, 401 bad/expired/access token or user gone |

Response: `{"accessToken", "refreshToken", "tokenType": "Bearer", "expiresIn": 900}`. These are the OAuth2 field names; `expiresIn` is in seconds.

| Decision | Why |
|---|---|
| Register creates a **new organization** with the user as its **ADMIN** | Joining an existing org would need its id from the request body, which must never be trusted. Adding users to an org is admin user management (later phase). |
| `@Transactional` on `register` | Organization and user are saved together or not at all. |
| Refresh re-reads the user with `findByIdAndOrganizationId` | Deleted users and role changes take effect; still a tenant-scoped query. |
| Emails lowercased with `Locale.ROOT` | Case-insensitive accounts. `Locale.ROOT` avoids the Turkish `I` → `ı` problem. |
| No `trim()` | `@Email` already rejects surrounding spaces (400), so a trim could never run. A test showed this. |
| Password `@Size(min = 8, max = 72)` | BCrypt only uses the first 72 bytes. |
| DTO records, not entities | Clients can't send `role` or `organizationId`, and `passwordHash` is never returned. |
| `@ResponseStatus` on `DuplicateResourceException` | Simple 409 until Phase 6's `@RestControllerAdvice`. |

`AuditorAware` now fills `created_by` with the logged-in user's id. During registration nobody is logged in, so the first organization and user have `created_by = NULL`.

---

## 6. Editor null-safety warnings (Spring 7 + JSpecify)

Spring Framework 7 and Spring Security 7 mark their APIs with JSpecify annotations (`@NullMarked` packages, `@Nullable` return values). VS Code's Java extension reads them and warns when a **method reference** to a Spring method is passed where the JDK's unannotated generics are expected, e.g. `Optional.map(Authentication::getPrincipal)` or `.csrf(AbstractHttpConfigurer::disable)`. These are editor hints, not compile errors. Fixes used:

- `JpaAuditingConfig`: a null check plus pattern-matching `instanceof` instead of an `Optional` chain.
- `SecurityConfig`: the lambda `csrf -> csrf.disable()` instead of a method reference.

---

## 7. Tests

### `JwtTokenProviderTest` (plain unit test, no Spring, about 0.1 s)

Round trip of claims; access/refresh not interchangeable; tampered token, token signed with another key, expired token (TTL `-1s`) and garbage all rejected; a weak secret fails at construction.

### `AuthSecurityIntegrationTest` (`@SpringBootTest` + MockMvc + Postgres container)

| Test | Proves |
|---|---|
| `registerCreatesAdminAndReturnsTokenPair` | 201, `Bearer`, `expiresIn` 900, role ADMIN |
| `protectedEndpointWithoutTokenReturns401` / `...WithInvalidTokenReturns401` | Deliverable: unauthenticated → 401 |
| `accessTokenSetsTenantContextForRequestAndItIsClearedAfterwards` | Deliverable: correct tenant context, cleared after the request |
| `loginWithCorrectPasswordReturnsTokensAndIgnoresEmailCase` | Login works; email case doesn't matter |
| `loginWithWrongPasswordOrUnknownEmailReturns401` | Both failures look the same |
| `registeringAnExistingEmailReturns409` | Duplicate email, even in different case |
| `invalidRegistrationReturns400` | Bean Validation |
| `refreshTokenIssuesNewPairButIsRejectedAsBearerToken` | Refresh works; tokens only work in their own place |
| `refreshForUserThatNoLongerExistsReturns401` | Refresh checks the database |
| `userRoleCannotDeleteProjectsOrTasks` | `USER` → 403 |
| `managerRolePassesDeleteRuleButNotUserManagement` | `MANAGER` passes the delete rule (404, no controller yet), but `/api/users` → 403 |

How it works:
- A **probe controller** (`GET /api/test/tenant`) is registered by a nested `@TestConfiguration`, so it exists only in this test class. It returns what the filter put in `TenantContext`.
- MockMvc runs the request on the test's thread, so the test can check that `TenantContext` was cleared afterwards.
- `USER` and `MANAGER` tokens are minted with `JwtTokenProvider`, because registration only creates ADMINs.
- MockMvc doesn't simulate the forward to `/error`, so the 409/401/400 responses were also checked with `curl` against the running app.

---

## 8. Where we changed the plan

| Plan said | We did | Why |
|---|---|---|
| `validateToken` + `extractClaims` | `parseAccessToken` / `parseRefreshToken` | One parse does both; also enforces the token type. |
| Claims `sub`, `org`, `role` | Also `email`, `type` | `type` keeps refresh tokens out of the filter; `email` fills `UserPrincipal.getUsername()`. |
| `UserService.register` / `loadUserByUsername` (Phase 4) | `AuthService` + `DatabaseUserDetailsService` now | The auth endpoints needed them in this phase. |
| Email "unique within the org" (Phase 4) | Globally unique (since Phase 2) | Login works with email + password alone. |
| Auth DTOs in Phase 5 | Added now | Needed by `AuthController`. |
| Package list had no controllers | New `controller/` package | Home for REST controllers. |
| Custom exceptions + handler in Phase 6 | Two exceptions now, using `@ResponseStatus` / `AuthenticationException` | Correct status codes now; Phase 6 adds the JSON error envelope. |

---

## 9. Verification

```bash
docker compose up -d
./mvnw test                   # 26 tests, 0 failures
./mvnw spring-boot:run        # no "generated security password" line any more

curl -i localhost:8080/api/projects                     # 401
curl -s -H 'Content-Type: application/json' \
  -d '{"organizationName":"Acme","email":"me@acme.example","password":"correct-horse-battery"}' \
  localhost:8080/api/auth/register                      # 201 + tokens
curl -i -H "Authorization: Bearer <accessToken>" localhost:8080/api/projects   # 404 (no controller yet), not 401
```

---

## 10. Next: Phase 4 (service layer)

1. `ProjectService` and `TaskService` read the org id only from `TenantContext.get()`.
2. Cross-tenant checks: a task's project and assignee must belong to the current organization.
3. `ResourceNotFoundException` for ids from another tenant.
4. Mockito unit tests, including cross-tenant rejection.
