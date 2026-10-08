-- Built-in sign-in hardening: lockout state shared by all instances, and sign-out that ends every session issued
-- before it (tokens issued earlier than sessions_valid_after are rejected).
alter table local_users
    add column failed_attempts      integer     not null default 0,
    add column locked_until         timestamptz,
    add column sessions_valid_after timestamptz not null default '1970-01-01T00:00:00Z';

-- Personal API tokens for scripts and AI clients (MCP). Only a SHA-256 of the token is stored; the hint is its
-- non-secret prefix, to recognise it in lists.
create table api_tokens (
    id           uuid         primary key,
    name         varchar(100) not null,
    owner        varchar(255) not null,
    roles        varchar(200) not null,
    token_hash   char(64)     not null unique,
    hint         varchar(16)  not null,
    created_at   timestamptz  not null,
    expires_at   timestamptz  not null,
    last_used_at timestamptz,
    revoked_at   timestamptz
);
create index api_tokens_owner_idx on api_tokens (owner);
