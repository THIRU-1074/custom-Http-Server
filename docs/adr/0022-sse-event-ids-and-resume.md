# ADR 0022: SSE event ids and Last-Event-ID

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. A client that reconnects to an SSE stream (a seat map) sends `Last-Event-ID` so the server can resume, but `EventStream` could not write an `id:` line and `Request` had no typed access to the header (#70).

## Decision
- `EventStream.event(id, name, data)` writes `id:` before `event:` and `data:`. `id` and `name` may be `null`. An id containing CR, LF or NUL throws `IllegalArgumentException`, since it would split the message or be ignored by clients. `event(name, data)` and `event(data)` delegate with a null id. `retry` and `comment` are unchanged.
- `Request.lastEventId()` returns the `Last-Event-ID` header or `null`. It is a header read; the request path is unchanged.
- `JoltTest` needs no change: `Result.events()` already carries the id (ADR 0020) and `call.header("Last-Event-ID", ..)` sends the reconnect header.

## Consequences
- Public API grows by one `EventStream` overload and `Request.lastEventId()`, at one `llms-extras.txt` line. jolt-core stays dependency-free.
- Resuming (which events to replay) is the app's job, or the `Hub` replay buffer of ADR 0023.
