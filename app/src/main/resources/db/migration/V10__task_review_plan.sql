-- The requester asked to review the specification even when triage rates the task low risk.
alter table tasks add column review_plan boolean not null default false;
