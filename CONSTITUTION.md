# Jolt Constitution

These principles are non-negotiable. A change that violates one is rejected, or the principle is amended first through an ADR in `docs/adr/`.

1. **Zero runtime dependencies in `jolt-core`.** Core uses the JDK only. Optional modules (`jolt-auth`, `jolt-mcp`) may depend on libraries; core never does.
2. **Secure by default.** Every limit is on out of the box. Every risky feature (CORS, route listing, MCP, static files) is opt-in.
3. **Small, Express-shaped public API.** Anything in an `internal` package is not API and may change without notice.
4. **Every behavior change ships with a test.** Bug fixes start with a failing test, committed together with the fix.
5. **No silent failure.** Every request gets a response. A handler exception becomes a 500, never a hung connection.
6. **Token-small is a feature.** Jolt should take the fewest tokens to build a server with. Growing the public API requires an ADR.
