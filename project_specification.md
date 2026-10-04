# Multi-Tenant Task & Project Management API

A production-ready, multi-tenant RESTful backend service built with **Spring Boot 3** and **PostgreSQL**. This project demonstrates enterprise-level Java development concepts including data isolation, stateless authentication, role-based access control, clean architecture, and containerized testing.

---

## 1. Project Overview & Features

### Core Capabilities
* **Multi-Tenancy:** Discriminator-column multi-tenant data architecture ensuring complete data isolation per organization.
* **Authentication & Security:** Stateless JWT-based authentication with custom organization context propagation.
* **Role-Based Access Control (RBAC):** Fine-grained security roles (`ADMIN`, `MANAGER`, `USER`) for scoped API access.
* **Project & Task Operations:** CRUD management for projects and tasks with cross-tenant reference prevention.
* **Global Error Handling:** Unified, structured JSON error responses via `@ControllerAdvice`.
* **Testing Rigor:** Automated integration test suite built with **Testcontainers** and **JUnit 5**.

---

## 2. System Architecture & Tech Stack

### Technology Stack
* **Language & Framework:** Java 17+, Spring Boot 3.x
* **Data Access:** Spring Data JPA, Hibernate, PostgreSQL
* **Security:** Spring Security 6.x, `jjwt` (Java JWT library)
* **Utilities:** Lombok, MapStruct (DTO mapping)
* **Testing:** JUnit 5, Mockito, Testcontainers

### Layered Architecture

```
                    ┌───────────────────────────────┐
                    │    REST Clients / Swagger     │
                    └───────────────┬───────────────┘
                                    │ HTTP / JSON
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        Spring Boot API Core                            │
│                                                                        │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Security Filter Chain (JWT Validator & Tenant Context Filter)   │  │
│  └────────────────────────────────┬─────────────────────────────────┘  │
│                                   │                                    │
│  ┌────────────────────────────────▼─────────────────────────────────┐  │
│  │  Controller Layer (@RestController, DTOs, @Valid Constraints)     │  │
│  └────────────────────────────────┬─────────────────────────────────┘  │
│                                   │ Data Mapping (MapStruct)           │
│  ┌────────────────────────────────▼─────────────────────────────────┐  │
│  │  Service Layer (@Service, Transactional Boundaries, Business)    │  │
│  └────────────────────────────────┬─────────────────────────────────┘  │
│                                   │ Entities                           │
│  ┌────────────────────────────────▼─────────────────────────────────┐  │
│  │  Data Access Layer (Spring Data JPA Repositories)                │  │
│  └────────────────────────────────┬─────────────────────────────────┘  │
└───────────────────────────────────┼────────────────────────────────────┘
                                    │ SQL
                                    ▼
                    ┌───────────────────────────────┐
                    │     PostgreSQL Database       │
                    └───────────────┬───────────────┘
```

---

## 3. Database & Multi-Tenancy Strategy

The application uses a **Discriminator Column (Shared Database, Shared Schema)** approach. Every domain record includes an `organization_id` column to guarantee complete tenant isolation.

### Entity Relationship Model

```
 ┌────────────────┐          ┌────────────────┐
 │  Organization  │ 1      * │      User      │
 ├────────────────┼──────────┼────────────────┤
 │ id (UUID)      │          │ id (UUID)      │
 │ name           │          │ org_id (FK)    │
 │ created_at     │          │ email, password│
 └───────┬────────┘          │ role (ENUM)    │
         │                   └───────┬────────┘
         │ 1                         │ 1
         │                           │
         │ *                         │ *
 ┌───────▼────────┐          ┌───────▼────────┐
 │    Project     │ 1      * │      Task      │
 ├────────────────┼──────────┼────────────────┤
 │ id (UUID)      │          │ id (UUID)      │
 │ org_id (FK)    │          │ org_id (FK)    │
 │ name           │          │ project_id(FK) │
 │ description    │          │ assignee_id(FK)│
 └────────────────┘          │ title, status  │
                             └────────────────┘
```

---

## 4. Implementation Roadmap

### Phase 1: Setup & Project Initialization
* Initialize project via Spring Initializr (`spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-security`, `spring-boot-starter-validation`, `postgresql`, `lombok`).
* Configure Maven/Gradle dependencies and local environment settings.

### Phase 2: Tenant Context & Security Layer
* Create `TenantContext` using `ThreadLocal<UUID>` for thread-safe organization context propagation across incoming requests.
* Build `JwtAuthenticationFilter` to extract JWT tokens from `Authorization` headers, validate signatures, populate tenant context, and register `SecurityContext`.
* Configure `SecurityFilterChain` bean to enforce endpoint protection and role requirements.

### Phase 3: Persistence & Domain Logic
* Define JPA entities (`Organization`, `User`, `Project`, `Task`) with relationship mappings and tenant ID attributes.
* Implement repository interfaces with repository query methods explicitly scoping queries to `organizationId`.
* Develop transactional services for managing projects and tasks, incorporating logic to prevent cross-tenant assignment/access.

### Phase 4: API Controllers & Exception Handling
* Build `@RestController` classes exposing RESTful routes for resources.
* Implement `@ControllerAdvice` for global exception interception, returning standard HTTP error payloads.

### Phase 5: Integration Testing
* Write full-suite integration tests using `@SpringBootTest`, `Testcontainers`, and JUnit 5 to test database interactions and cross-tenant access boundaries against real PostgreSQL instances.

---

## 5. Resume Showcase Guide

When highlighting this project on your resume or during interviews, focus on key software architecture choices and engineering patterns:

### Resume Bullet Points Example

> **Multi-Tenant Task API (Spring Boot 3, Spring Security, PostgreSQL, Testcontainers)**
> * Designed and built a multi-tenant task and project management RESTful service using Spring Boot 3 and PostgreSQL.
> * Engineered tenant isolation using custom ThreadLocal context management combined with Spring Security JWT filters.
> * Integrated global error handling (`@ControllerAdvice`), custom validation constraints, and clean DTO mapping layer using MapStruct.
> * Implemented integration test coverage using **Testcontainers** and JUnit 5 to validate database security rules against real PostgreSQL containers.
