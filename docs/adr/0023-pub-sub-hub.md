# ADR 0023: Pub/sub hub for SSE and WebSocket

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. A seat map must push each change to everyone viewing a show. `res.sse()` and WebSocket sessions serve one connection each on that connection's thread, so an app had no safe way to fan an event out to many of them: writing to every stream from the publishing thread lets one slow client stall all the others (#62).

## Decision
- `Hub<T>` in `in.jolt`, created by `Hub.create()` (queue of 64 per subscriber, at most 10000 subscribers, no replay) or `Hub.create(queueSize, maxSubscribers, replay)`, which requires `queueSize >= 1`, `maxSubscribers >= 1` and `0 <= replay <= queueSize`.
- `publish(topic, message)` gives the message an id, queues it for every subscriber of the topic and returns how many took it. It never blocks or writes to a socket. Ids come from one counter per hub seeded with the current time in microseconds, so they increase within a topic and keep increasing across restarts in practice.
- Each subscriber has a bounded queue. When a publish finds it full, the subscriber is dropped: unsubscribed, its pending messages discarded, `<name>_dropped_total` incremented and a WARNING logged. Drop, not block, is the policy, because a publisher must never wait on a client. A dropped SSE client sees its stream end and reconnects with `Last-Event-ID`.
- `subscribe(topic, lastEventId, Sink<T>)` drains the queue on a virtual thread; `Sink.send(id, message)` throwing ends the subscription. It returns a `Hub.Subscription` (`isOpen`, `close`). Over `maxSubscribers` it throws `HttpException` 503, so an HTTP handler answers 503.
- `hub.sse(topic, req, res)` subscribes, opens `res.sse()` and drains on the handler's own thread until the client leaves or is dropped. Each message is one event with its id and no name; a `String` is sent as is, anything else as JSON through the app's mapper. A comment every 15 s keeps proxies open and finds dead clients when nothing is published; otherwise a gone client is removed on the next write that fails.
- `hub.ws(req -> topic)` returns a `WebSocketHandler` that subscribes each session on open and unsubscribes it on close. Messages go out as text (String or JSON). WebSocket has no standard resume, so ids are not sent and nothing is replayed.
- With `replay > 0` each topic keeps its last `replay` messages; a numeric `lastEventId` replays those after it. Topics with a replay buffer stay in memory after their last subscriber leaves (at most `replay` messages each); without replay an empty topic is removed.
- `hub.metrics(app.metrics(), name)` registers the gauge `<name>_subscribers` and the counter `<name>_dropped_total` in the app's registry (ADR 0021).

## Consequences
- Public API grows by `Hub` (with `Sink` and `Subscription`) at one `llms-extras.txt` line. jolt-core stays dependency-free; `Request`, `Response` and `AppMetrics` gain package-private helpers only.
- A hub SSE handler holds its connection's thread while connected, as any SSE handler does. `Server.close()` still waits its 5 s grace for such handlers before interrupting them.
- A client that closes while nothing is published is noticed within 15 s (SSE) or at its close frame (WebSocket).
