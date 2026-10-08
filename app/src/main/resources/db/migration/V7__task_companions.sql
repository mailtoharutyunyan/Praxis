-- Other repositories a task changes together with its primary one (ADR-0006): [{alias, kind, cloneUrl, baseBranch}].
alter table tasks add column companions jsonb not null default '[]'::jsonb;
