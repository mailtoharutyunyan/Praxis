# ADR-0001: Spring AI 2.0 as the LLM and MCP framework
- Status: Proposed
- Date: 2026-10-08

## Context
Agentic SDLC calls LLMs in several roles (triage, planner, coder, reviewer). It must support Anthropic Claude (the primary model), OpenAI, cloud-hosted models (Bedrock, Azure OpenAI, Vertex/Google GenAI) and local Ollama, each selectable per role. It talks to Jira and code hosts, partly through MCP servers that use the Streamable HTTP transport. The service runs on Java 25, Spring Boot 4.1.1 and WebFlux, with long multi-step tool loops where prompt caching drives cost.

Versions verified on Maven Central, 2026-10-08:
- Spring AI 2.0.1 GA (2026-08-21), built for Boot 4.0/4.1.
- LangChain4j 1.22.0. Its Boot 4 starter is `1.22.0-beta32`, and its agentic and MCP modules are still beta.

## Options considered

### A. Spring AI 2.0.1
**Pros**
- GA and Boot 4 native.
- One `ChatClient` API across all required providers.
- MCP client and server with WebFlux Streamable HTTP transports, MCP Java SDK 2.0 (spec 2025-11-25).
- Anthropic prompt caching with configurable strategy and TTL, including tool-result caching. Extended thinking.
- Micrometer observations for chat, advisors and tools out of the box. Maintained by the Spring team.

**Cons**
- The Anthropic integration uses the official Anthropic Java SDK on OkHttp, so a stream holds a `boundedElastic` thread for its duration.
- `ToolCallback.call()` is synchronous.
- No workflow or multi-agent engine; human-in-the-loop is not provided.
- The 2.0 upgrade broke many APIs (Jackson 3, property keys).

### B. LangChain4j 1.22
**Pros**
- Richer agent toolbox: `agentic` module (sequence, loop, supervisor), `humanInTheLoop` with suspend and resume, guardrails.
- Faster release cadence and a larger community (13.2k vs 9.5k GitHub stars).
- Reactor return types via `langchain4j-reactor`.

**Cons**
- The Boot 4 starter, agentic and MCP modules are beta.
- MCP server support is stdio only (community module).
- A separate `langchain4j.*` configuration model.
- Human-in-the-loop state persistence is an interface we would implement anyway.

### C. Both: LangChain4j for orchestration, Spring AI for models and MCP
**Pros**
- Best-of-breed per layer.

**Cons**
- Two abstractions for messages, tools and memory.
- Two Jackson and HTTP stacks.
- Twice the upgrade surface. The research recommended against it.

## Decision
Use **Spring AI 2.0.1** for model access, tool calling, structured output and MCP. Orchestration is our own (ADR-0002, ADR-0003), so Spring AI's lack of a workflow engine does not matter here. Do not mix frameworks for model calls.

## Consequences

### Positive
- Providers are swapped through configuration, per role.
- MCP clients and servers use the same reactive stack as the API.
- Token, latency and tool metrics come free through Micrometer.

### Negative
- Blocking SDK calls sit on `Schedulers.boundedElastic()`.
  - We set `reactor.schedulers.defaultBoundedElasticOnVirtualThreads=true` before Reactor loads (`AgenticSdlcApplication.configureReactor()`), so that pool is not capped at 10 × cores.
  - `spring.threads.virtual.enabled` alone does **not** change Reactor's scheduler.
- Worker concurrency must still be capped explicitly (number of concurrent runs and tool calls per run). Otherwise model streams, Docker and JGit compete without bound.
- Spring Boot 4 defaults to Jackson 3 (`tools.jackson`). Some provider SDKs may bring Jackson 2 with them. Both can coexist, but `run_events` serialisation pins Jackson 3.

### Follow-ups
- **M3:**
  - Confirm the exact 2.0.1 starter artifact IDs and how to disable providers that have no credentials (several `ChatModel` beans on the classpath).
  - Confirm how to cap tool-loop iterations, or use user-controlled tool execution (ADR-0003).
- `Usage` already tracks cache-read and cache-write tokens. Fill them from Spring AI response metadata so cost stays correct with caching.
- The root POM enables `dependencyConvergence`. Bedrock, Azure and Vertex starters will probably need explicit exclusions; resolve them rather than disabling the rule.
- Revisit LangChain4j if its agentic module reaches GA *and* we outgrow our own orchestration.
