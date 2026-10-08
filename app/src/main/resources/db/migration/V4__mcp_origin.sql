-- Tasks submitted by AI clients over MCP (ADR-0005).
alter table tasks drop constraint tasks_origin_ck;
alter table tasks add constraint tasks_origin_ck check (origin in ('PROMPT', 'JIRA', 'GITHUB_ISSUE', 'GITLAB_ISSUE',
                                                                  'AZURE_DEVOPS_WORK_ITEM', 'SLACK', 'MCP'));
