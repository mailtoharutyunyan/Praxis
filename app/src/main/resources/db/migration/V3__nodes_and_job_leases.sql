-- Workspace affinity: a run's working copy lives on the node that last claimed it. Other nodes take the run over
-- only after that node stops sending heartbeats (its workspace is then presumed lost).
alter table runs add column workspace_node varchar(128);

create table worker_nodes (
    node         varchar(128) primary key,
    heartbeat_at timestamptz  not null
);

-- Cluster-wide background jobs (ticket updates, pull request polling) run on one instance at a time.
create table job_leases (
    name       varchar(64)  primary key,
    owner      varchar(128) not null,
    expires_at timestamptz  not null
);

-- Claims take the oldest change first, so runs with an expired lease are not starved by fresh ones.
drop index runs_claimable_idx;
create index runs_claimable_idx on runs (updated_at, id)
    where state in ('RECEIVED', 'TRIAGING', 'PREPARING_CONTEXT', 'SPECIFYING', 'IMPLEMENTING',
                    'VERIFYING', 'REVIEWING', 'PUBLISHING');
