-- Runtime integrations (ADR-0007): plain settings as JSON, secrets encrypted (AES-GCM, base64).
create table connectors (
    id         varchar(40)  primary key,
    status     varchar(16)  not null,
    config     jsonb        not null default '{}'::jsonb,
    secrets    text,
    updated_at timestamptz  not null,
    updated_by varchar(255) not null,
    constraint connectors_status_ck check (status in ('CONFIGURED', 'SKIPPED'))
);

-- Built-in sign-in for installs without an identity provider (agentic.security.mode=local).
create table local_users (
    username      varchar(100) primary key,
    password_hash varchar(200) not null,
    roles         varchar(200) not null,
    created_at    timestamptz  not null
);

-- Small application secrets, e.g. the local token signing key (encrypted).
create table app_secrets (
    name       varchar(100) primary key,
    value      text         not null,
    created_at timestamptz  not null
);
