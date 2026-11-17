# ADR 0014: JoltTest support for streaming and SSE

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. `res.sse()` and `res.stream()` threw `IllegalStateException` under `JoltTest` because there is no live connection, so apps could not unit-test SSE endpoints without a socket (#69).

## Decision
- `JoltTest` gives each call an in-memory `Live` connection whose `StreamSink` collects the written bytes. The server path is untouched: `Response`, `ConnectionHandler`, `EventStream` and `ResponseStream` change only in a Javadoc sentence; the work is in `JoltTest` and one package-private class, `StreamCapture`.
- The handler runs to completion on the calling thread. `Result.bytes()` and `text()` then return the streamed body, `status()` and `header()` the committed head, and `Result.events()` the parsed `List<JoltTest.Event>`; `Event` is a record `(name, data, id)`, with `null` for fields not sent. Parsing follows the SSE rules: `data:` lines join with newlines, comments and `retry:` are ignored, an event without data and a final unterminated event are dropped.
- `Call.maxEvents(n)` ends a stream after `n` SSE events: the next write throws `IOException`, as for a client that left, so an endless handler loop exits through its normal disconnect path. `Call.timeout(Duration)` runs the handler on a virtual thread and, on expiry, drops the stream and interrupts it, for handlers blocked between events. Both reject non-positive values.
- A handler that throws after streaming began keeps its status and output; the throwable is available as `Result.streamError()` (the server logs it and truncates the body). The limit's own `IOException` and the interrupt after a timeout are not reported as errors. A throw before streaming is the usual error response.
- WebSocket upgrades still answer 501, as before.

## Consequences
- Public API grows by `JoltTest.Event`, `Call.maxEvents`, `Call.timeout`, `Result.events` and `Result.streamError`, at one `llms-extras.txt` line. jolt-core stays dependency-free.
- `Result.bytes()` and `text()` return the streamed body, not the empty buffered body, for streaming responses.
- Without `maxEvents` or `timeout`, a handler that never returns hangs the test, as it would hang a client.
- Metrics counted for a streaming call under `JoltTest` use the buffered-response path, so the stream series are not exercised there.
