# ADR 0026: Keep-alive poller

## Status
Accepted.

## Context
A keep-alive connection spends most of its life waiting for the next request. Until now its virtual thread blocked in `read()` for that time, and the connection held one `maxConnections` permit until `keepAliveTimeout`. Idle connections could use every permit and turn new clients away with 503 (#88). Tomcat's NIO connector avoids tying a thread to an idle connection: a poller thread watches idle sockets with one `Selector` and gives a socket to a worker only when it becomes readable.

## Decision
- `Poller` (internal) owns one `Selector` and one platform thread. A handler that has answered a keep-alive request and has no buffered bytes hands its connection to the poller and ends its virtual thread. A new connection is parked the same way until its first request.
- Registration happens on the poller thread, fed by a queue, because `register` blocks while the selector is selecting.
- When a connection becomes readable, the poller cancels its key, flushes the cancellation with `selectNow`, returns the channel to blocking mode and runs `ConnectionHandler.resume` on the executor. Parsing and handling stay blocking code on a virtual thread.
- The poller checks every parked connection against `keepAliveTimeout` on each tick (half the timeout, between 10 ms and 1 s) and closes expired ones.
- The plain `ServerSocket` becomes `ServerSocketChannel.open().socket()`, so accepted sockets have a channel. TLS keeps the blocking wait: an `SSLSocket` has no channel and selecting on TLS needs an `SSLEngine`, which is out of scope. Sockets without a channel take the same path.
- Idle connections still count against `maxConnections`, as in Tomcat. At the limit the longest-idle connection, parked or waiting, is closed to admit a new one; 503 is sent only when every connection has a request in flight.
- The permit and metrics are released by a close callback that runs once, wherever the connection ends (handler thread, poller, eviction or shutdown), instead of when the handler's task returns.

## Consequences
- An idle plain connection costs a selector registration and a small object, not a thread, and its keep-alive expiry no longer depends on a blocking read.
- A connection is served by different virtual threads over its life. Handler state is plain fields; the queue and the executor hand-off give the happens-before edge.
- One more thread (`jolt-poller`) per plain server, and one more hop for the first request of every connection.
- Virtual threads already make a parked idle connection cheap, so the gain is smaller than with platform threads; the main practical effects are timeout handling in one place and idle connections being reclaimable under pressure.
- `jolt-core` still takes no dependencies; the poller uses `java.nio` only.
