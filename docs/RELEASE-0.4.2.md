# Jolt 0.4.2 release notes

One issue, #88: idle keep-alive connections no longer hold the server's connection slots or a thread. ADR 0026.

## #88 Keep-alive poller and idle-connection eviction
- **Poller.** Between requests a plain-socket connection waits in `Poller`, one `Selector` and one thread, instead of blocking a virtual thread in `read()`. When the socket becomes readable the poller runs the connection again on the executor. Connections idle for `keepAliveTimeout` are closed by the poller. New connections wait there until their first request.
- **Eviction.** Idle connections still count towards `maxConnections`. When the limit is reached the connection idle longest is closed to admit the new one. A new connection gets 503 only when every connection has a request in flight.
- **Release path.** A connection's permit and metrics are released by a close callback that runs once, wherever the connection ends.
- **TLS.** TLS connections keep waiting on their own thread, because an `SSLSocket` has no channel to select on.
- The plain server socket is now opened as a `ServerSocketChannel`. No public API changes and no new dependencies.

## Behaviour changes
- The server may close an idle keep-alive connection when the connection limit is reached. A client that reuses it gets a reset and reconnects, the usual keep-alive race.
- Code that saturated `maxConnections` with idle keep-alive connections and expected 503 for the next one now gets served; hold requests in flight to saturate it.
- The first request on each plain connection takes one extra hop through the poller.

## Not included
- Selecting on TLS connections (needs an `SSLEngine`).
- #36 Maven Central publishing.
