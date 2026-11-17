# ADR 0017: SSE resume, streaming in JoltTest and a pub/sub hub

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. A booking app pushes seat-map changes to every viewer of a show. That needs event ids so a reconnecting client can resume (#70), a way to unit-test SSE endpoints (#69) and a safe fan-out from one publisher to many connections (#62).

## Decision
1. `EventStream.event(id, name, data)` writes an `id:` line before `event:` and `data:`; an id containing CR, LF or NUL is rejected with `IllegalArgumentException`, since it would split or end the message. `event(name, data)` delegates with a null id. `Request.lastEventId()` returns the `Last-Event-ID` header or null; it is a header read, so the request path is unchanged.

## Consequences
- `EventStream` and `Request` gain one method each, at no `llms-extras.txt` growth beyond the existing SSE line.
