-- Per-run cursor of the last event reported back to the originating ticket (Jira comments).
create table ticket_sync (
    run_id     uuid        primary key references runs (id),
    last_seq   bigint      not null check (last_seq >= 0),
    updated_at timestamptz not null
);

-- Ticket updates scan recently changed runs of one origin.
create index runs_updated_at_idx on runs (updated_at);
