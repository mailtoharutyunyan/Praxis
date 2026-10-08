-- Facts agents learned about repositories, with the code that supports them (core.memory.RepoFact).
create table repo_facts (
    id            uuid          primary key,
    repository    varchar(512)  not null,
    fact          varchar(300)  not null,
    citations     jsonb         not null,
    status        varchar(16)   not null,
    source_run_id uuid          references runs (id),
    created_at    timestamptz   not null,
    expires_at    timestamptz,
    last_used_at  timestamptz,
    constraint repo_facts_status_ck check (status in ('CANDIDATE', 'ACTIVE', 'DISABLED')),
    constraint repo_facts_active_expiry_ck check (status <> 'ACTIVE' or expires_at is not null)
);

create index repo_facts_repository_idx on repo_facts (repository, status);
create index repo_facts_source_run_idx on repo_facts (source_run_id);
