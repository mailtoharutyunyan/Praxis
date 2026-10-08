-- Enum-valued columns are stored by constant name. Their check constraints mirror the Java enums in core;
-- SchemaContractTest fails the build if they drift apart.

-- Tasks are immutable once received. A task can have several runs (retries, re-runs after rejection).
create table tasks (
    id              uuid primary key,
    origin          varchar(32)   not null,
    external_ref    varchar(255),
    title           varchar(500)  not null,
    description     text          not null,
    scm_kind        varchar(32)   not null,
    clone_url       varchar(2000) not null,
    base_branch     varchar(255),
    trust           varchar(16)   not null,
    requested_by    varchar(255)  not null,
    idempotency_key varchar(255),
    created_at      timestamptz   not null,
    constraint tasks_origin_ck check (origin in ('PROMPT', 'JIRA', 'GITHUB_ISSUE', 'GITLAB_ISSUE',
                                                 'AZURE_DEVOPS_WORK_ITEM', 'SLACK')),
    constraint tasks_scm_kind_ck check (scm_kind in ('GITHUB', 'GITLAB', 'BITBUCKET', 'AZURE_DEVOPS')),
    constraint tasks_trust_ck check (trust in ('TRUSTED', 'UNTRUSTED')),
    constraint tasks_clone_url_https_ck check (clone_url like 'https://%')
);

create unique index tasks_idempotency_key_uq on tasks (requested_by, idempotency_key)
    where idempotency_key is not null;
create index tasks_origin_external_ref_idx on tasks (origin, external_ref);

create table runs (
    id                 uuid primary key,
    task_id            uuid          not null references tasks (id),
    state              varchar(32)   not null,
    risk               varchar(16),
    gates              varchar(32)[],
    pending_gate       varchar(32),
    resume_state       varchar(32),
    fix_iterations     integer       not null default 0,
    review_loops       integer       not null default 0,
    input_tokens       bigint        not null default 0,
    output_tokens      bigint        not null default 0,
    cache_read_tokens  bigint        not null default 0,
    cache_write_tokens bigint        not null default 0,
    cost_micro_usd     bigint        not null default 0,
    -- Per-run event sequence; the next event gets last_event_seq + 1, assigned under this row's lock.
    last_event_seq     bigint        not null default 0,
    -- Worker lease: a run is processed by at most one instance; an expired lease can be taken over.
    -- Every write is fenced by version (insert 0, +1 per update) so a worker whose lease was taken over
    -- cannot overwrite the new owner's progress.
    lease_owner        varchar(128),
    lease_expires_at   timestamptz,
    version            bigint        not null default 0,
    created_at         timestamptz   not null,
    updated_at         timestamptz   not null,
    constraint runs_state_ck check (state in ('RECEIVED', 'TRIAGING', 'PREPARING_CONTEXT', 'SPECIFYING',
        'IMPLEMENTING', 'VERIFYING', 'REVIEWING', 'AWAITING_APPROVAL', 'PUBLISHING', 'PR_OPEN', 'NEEDS_HUMAN',
        'DONE', 'FAILED', 'CANCELLED')),
    constraint runs_risk_ck check (risk in ('LOW', 'MEDIUM', 'HIGH')),
    constraint runs_gates_ck check (gates <@ array['SPEC', 'IMPLEMENTATION', 'PUBLISH']::varchar(32)[]
                                    and 'PUBLISH' = any (gates)),
    constraint runs_pending_gate_ck check ((state = 'AWAITING_APPROVAL') = (pending_gate is not null)
                                           and (pending_gate is null or pending_gate = any (gates))),
    constraint runs_resume_state_ck check ((state = 'NEEDS_HUMAN') = (resume_state is not null)),
    constraint runs_triage_ck check ((risk is null) = (gates is null)),
    constraint runs_counters_ck check (fix_iterations >= 0 and review_loops >= 0 and input_tokens >= 0
        and output_tokens >= 0 and cache_read_tokens >= 0 and cache_write_tokens >= 0 and cost_micro_usd >= 0
        and last_event_seq >= 0 and version >= 0)
);

create index runs_task_id_idx on runs (task_id);
-- Worker polling: live working runs whose lease is free or expired. The state list must equal
-- RunState.working(), and worker queries must repeat it verbatim for the planner to use this index.
create index runs_claimable_idx on runs (lease_expires_at nulls first)
    where state in ('RECEIVED', 'TRIAGING', 'PREPARING_CONTEXT', 'SPECIFYING', 'IMPLEMENTING',
                    'VERIFYING', 'REVIEWING', 'PUBLISHING');

-- Append-only: the application never updates or deletes rows here.
create table run_events (
    run_id      uuid         not null references runs (id),
    seq         bigint       not null,
    type        varchar(64)  not null,
    actor       varchar(255) not null,
    -- Producers must strip \u0000 from text: Postgres rejects it in jsonb and text.
    payload     jsonb        not null default '{}'::jsonb,
    occurred_at timestamptz  not null,
    primary key (run_id, seq),
    constraint run_events_seq_ck check (seq > 0)
);
