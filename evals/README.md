# Evaluation suites

Measure the agent on **your own** history before trusting it with new work, and re-run the suite whenever prompts,
models or limits change.

## Building a case from a merged fix
1. Pick a closed ticket whose fix was merged with tests.
2. Push a branch or tag at the **parent** of the fix commit, e.g. `eval/SHOP-123-base`. This is the case's `ref`.
3. Copy the tests the fix added or changed into `<case>/tests/`, at their repository paths. They are the hidden
   files: they are added only for grading, never shown to the agent.
4. Write `fail-to-pass` commands that run those tests (they fail at the base, pass after a correct fix), and
   `pass-to-pass` commands for the rest of the suite (regressions).
5. Paste the original ticket text as `description`.

Start with 30–50 cases spread across your repositories and task types.

## Running
```bash
export ANTHROPIC_API_KEY=...            # and any SCM tokens needed to clone
java -jar app/target/app-*.jar \
  --agentic.eval.suite=evals/my-suite.yaml --agentic.eval.output=target/eval \
  --agentic.eval.min-pass-rate=0.6      # exit code 1 below this, for CI
```
The pipeline is the production one (same stages, models, sandbox and limits). Gates before publishing are
auto-approved, the run stops at the PUBLISH gate, and nothing is ever pushed.

## Reading the report
- **pass@1**: the share of all trials that passed.
- **pass^k**: the share of cases where *all* k trials passed. It measures consistency, which matters more than a single lucky success.
- **mean cost**, **tokens** and **median duration**: for budgets and capacity planning.
- **Failed checks and notes** per trial (escalation reasons, timeouts): read the transcripts (the run events) of failing trials before changing prompts.
