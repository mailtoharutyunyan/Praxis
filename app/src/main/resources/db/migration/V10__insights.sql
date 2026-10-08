-- Delivery insights (R2dbcInsightsStore) select runs by creation time.
create index runs_created_at_idx on runs (created_at);

-- Time to pull request: a run's first transition to PR_OPEN. Queries must repeat this predicate verbatim
-- for the planner to use this index.
create index run_events_pr_open_idx on run_events (run_id, occurred_at)
    where type = 'STATE_CHANGED' and payload ->> 'to' = 'PR_OPEN';
