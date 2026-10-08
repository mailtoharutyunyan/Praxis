-- Find the run behind a pull request from a review comment or CI webhook (PullRequestFeedback).
create index run_events_pull_request_url_idx on run_events ((payload ->> 'url'))
    where type = 'ARTIFACT_PRODUCED' and payload ->> 'kind' = 'pull-request';
