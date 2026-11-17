# ADR 0016: Agent identity, audit, budgets and trace propagation

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. `jolt-mcp` lets agents call routes, but an operator could not tell which agent made a call, keep a record of tool calls, or stop one agent from looping on an expensive tool. Requests made through `/mcp` also lost the caller's request id and trace context, so logs from the tool route could not be joined to the agent's request.

## Decision
- `McpOptions` gains four settings, each a fluent copy like the existing ones:
  - `agent(Function<Request, String>)` names the caller from the `/mcp` request after the `auth` middleware has run, for example `req -> (String) req.attr("user")`. A null or blank result is `"anonymous"`. This is the only identity budgets and audit trust.
  - `audit(Consumer<ToolCall>)` receives one record per `tools/call` that names a known tool. Without it, Jolt logs one INFO line on the `in.jolt.mcp` System.Logger (`tool=... agent=... status=... NNms [id=...]`), with control characters replaced.
  - `auditArguments(boolean)`, off by default because tool arguments may hold secrets. When on, `ToolCall.arguments()` is a read-only copy and the default log line includes them.
  - `budget(String tool, int calls, Duration window)` caps calls per agent in a fixed window; `"*"` counts every tool. Several budgets can apply to one call. Over budget, the call returns a tool result with `isError: true` and `Budget exceeded for agent <a> on <tool>; retry in <n>s`, which the agent can read, rather than an HTTP 429 the MCP client may hide. Each budget tracks at most 10000 agents, least recently used evicted.
- `ToolCall` is a public record: `at, agent, client, tool, method, path, status, took, requestId, arguments`. `client` is the `User-Agent` header truncated to 100 characters: an untrusted label, never used for decisions. `status` is the route's HTTP status, 429 for a budget refusal and 400 for missing path arguments. Unknown tool names are JSON-RPC errors and are not audited.
- The fixed-window counter is moved from `RateLimit` into `in.jolt.internal.limit.FixedWindow` and reused by budgets. `RateLimit` behaviour and messages are unchanged.
- `RequestId.middleware()` also reads a W3C `traceparent` (lower-case hex, version not `ff`, non-zero ids, no extra fields for version `00`) and exposes its trace id as `req.traceId()`; `Logger.requests()` appends `trace=<id>`. Invalid values are ignored.
- `Mcp` forwards `X-Request-Id`, `traceparent` and `tracestate` to the inner request, in addition to `Authorization` and `Cookie`.

## Alternatives rejected
- Using MCP `initialize` `clientInfo.name` as the client label: the endpoint is stateless (no `Mcp-Session-Id`), so later calls cannot be tied to it. `User-Agent` is available on every call.
- Echoing `traceparent` on responses: the W3C spec defines no response header for it, so it is not sent.
- Budgets as HTTP 429: MCP clients surface JSON-RPC results to the model more reliably than transport errors.

## Consequences
- jolt-core stays dependency-free and grows by `Request.traceId()`. jolt-mcp grows by `ToolCall` and four `McpOptions` settings; the `McpOptions` canonical constructor gains components, so code calling it directly (not through `defaults()`) must be updated.
- Apps that already use `jolt-mcp` now get one INFO log line per tool call; pass `.audit(call -> {})` to silence it.
- Budgets and audit records are per process; a second instance keeps its own counters.
