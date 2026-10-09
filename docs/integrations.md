# Jira and Slack

Starting runs from Jira issues and Slack, and following them there.

[← Back to the README](../README.md)

## Jira
Label an issue `agentic` (or whatever `agentic.jira.trigger-label` is) to start a run. A run starts when the issue is created with the label, or when the label is added later. `POST /api/v1/webhooks/jira` accepts two senders:
- **Jira admin webhook** (events: issue created and issue updated) with a secret. Requests are verified with `X-Hub-Signature: sha256=…` over the raw body. Retries reuse `X-Atlassian-Webhook-Identifier` (Cloud) or the body `timestamp` (Data Center) and map to the same run.
- **Jira Automation "Send web request"** with header `X-Agentic-Webhook-Token: <agentic.jira.automation-token>` and body `{"key": "{{issue.key}}"}`.

The webhook body only names the issue. The summary, description (rich text converted to plain text) and labels are read back from Jira (REST v3 on Cloud, REST v2 on Data Center), and the project key selects the repository (`agentic.jira.projects.<KEY>`). Ticket text is untrusted: it always passes the SPEC gate and is framed as data for the models.

The run's progress is posted back as issue comments, once each, through a durable cursor:
- run started;
- waiting at a gate;
- pull request opened;
- needs a human (with the reason);
- failed, cancelled, or done.

```yaml
agentic:
  jira:
    enabled: true
    deployment: cloud              # or data-center (REST v2, plain-text comments)
    base-url: https://acme.atlassian.net
    email: bot@acme.com            # Cloud: Basic email:api-token; leave blank for a Data Center PAT
    api-token: ${JIRA_API_TOKEN}
    webhook-secret: ${JIRA_WEBHOOK_SECRET}
    run-link-base: https://agentic.example.com/#/runs/
    projects:
      SHOP: { kind: GITHUB, clone-url: https://github.com/acme/shop.git, base-branch: main }
```


### Local Jira Data Center
`dev/jira/compose.yaml` runs Jira Software Data Center with its own Postgres on http://localhost:8090:

1. `docker compose -p agentic-jira -f dev/jira/compose.yaml up -d`, then open the setup wizard and paste a [timebomb license](https://developer.atlassian.com/platform/marketplace/timebomb-licenses-for-testing-server-apps/).
2. Create a project and a personal access token (profile → Personal Access Tokens). Basic auth is disabled in Jira 11, so use the token with an empty `email`.
3. Register the webhook. On Data Center the secret goes in `configuration.SECRET`; a top-level `secret` field is silently ignored, and the requests then arrive unsigned:
   ```bash
   curl -X POST -H "Authorization: Bearer $JIRA_PAT" -H 'Content-Type: application/json' \
     http://localhost:8090/rest/jira-webhook/1.0/webhooks -d '{"name":"agentic-sdlc",
     "url":"http://host.docker.internal:8080/api/v1/webhooks/jira","active":true,
     "events":["jira:issue_created","jira:issue_updated"],
     "configuration":{"EXCLUDE_BODY":"false","SECRET":"'"$JIRA_WEBHOOK_SECRET"'"}}'
   ```
4. Start the app with `--agentic.jira.deployment=data-center --agentic.jira.base-url=http://localhost:8090` and the token and secret in `AGENTIC_JIRA_APITOKEN` and `AGENTIC_JIRA_WEBHOOKSECRET`, then add the `agentic` label to an issue.


## Slack
Start runs from Slack with a slash command, and follow them in a thread:
```
/agentic https://github.com/acme/shop.git Add a /health endpoint with a test
/agentic Add a /health endpoint with a test        # uses the connector's default repository
```
1. Create a Slack app with the bot scopes `chat:write` and `commands`, and install it in your workspace.
2. Add the slash command `/agentic` with the request URL `https://<public URL>/api/v1/webhooks/slack/commands`. Settings → Slack shows the exact URL.
3. In Settings → Slack, enter the bot token (`xoxb-…`), the signing secret, the **allowed channels and/or users** (Slack IDs such as `C0123ABCD`, `U0123ABCD`; commands from anywhere else are refused) and, optionally, a default repository.
4. Invite the app to the channels where it is used.

Each request is verified with the signing secret (Slack's `v0` HMAC over timestamp and body), and requests older than five minutes are rejected. The app posts the request in the channel and runs the task from that thread. Progress (gates, the pull request, the outcome) is posted as thread replies. Slack text is untrusted, like a ticket, so it always passes the SPEC gate, and the echoed request is escaped so it cannot mention `@channel`.
