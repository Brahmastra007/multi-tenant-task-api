-- Initial schema: organizations (tenants), users, projects, tasks.
-- Every tenant-owned table carries org_id; composite foreign keys make the
-- database itself reject a task that references another tenant's project or user.

CREATE TABLE organizations (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    created_by  UUID
);

CREATE TABLE users (
    id             UUID         PRIMARY KEY,
    org_id         UUID         NOT NULL REFERENCES organizations (id),
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    role           VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    created_by     UUID,
    CONSTRAINT uq_users_email      UNIQUE (email),
    CONSTRAINT uq_users_id_org     UNIQUE (id, org_id),  -- target for tenant-safe composite FKs
    CONSTRAINT ck_users_role       CHECK (role IN ('ADMIN', 'MANAGER', 'USER'))
);

CREATE TABLE projects (
    id           UUID         PRIMARY KEY,
    org_id       UUID         NOT NULL REFERENCES organizations (id),
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    created_by   UUID,
    CONSTRAINT uq_projects_id_org  UNIQUE (id, org_id)   -- target for tenant-safe composite FKs
);

CREATE TABLE tasks (
    id           UUID         PRIMARY KEY,
    org_id       UUID         NOT NULL REFERENCES organizations (id),
    project_id   UUID         NOT NULL,
    assignee_id  UUID,
    title        VARCHAR(255) NOT NULL,
    description  TEXT,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    created_by   UUID,
    CONSTRAINT ck_tasks_status CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    -- Project and assignee must belong to the same organization as the task
    CONSTRAINT fk_tasks_project  FOREIGN KEY (project_id, org_id)
        REFERENCES projects (id, org_id) ON DELETE CASCADE,
    CONSTRAINT fk_tasks_assignee FOREIGN KEY (assignee_id, org_id)
        REFERENCES users (id, org_id) ON DELETE SET NULL (assignee_id)
);

-- Every query is filtered by org_id, so each tenant column needs an index
CREATE INDEX idx_users_org_id         ON users (org_id);
CREATE INDEX idx_projects_org_id      ON projects (org_id);
CREATE INDEX idx_tasks_org_id         ON tasks (org_id);
-- Postgres does not index foreign key columns automatically
CREATE INDEX idx_tasks_project_id     ON tasks (project_id);
CREATE INDEX idx_tasks_assignee_id    ON tasks (assignee_id);
