# ADR 0002: Express-parity API growth

Status: accepted

## Context

Common server tasks (redirects, form posts, request logging, rate limiting, env config, graceful shutdown) force users to hand-roll code. Each is a few lines in Express.

## Decision

Add, all JDK-only and opt-in where they carry risk:

1. `Response.redirect(url)` and `redirect(status, url)`.
2. `Request.form()` for `application/x-www-form-urlencoded` bodies, and `Request.remoteAddress()`.
3. `Logger.requests()` middleware (System.Logger; method, path, status, duration only).
4. `RateLimit.perIp(max, window)` middleware with bounded memory.
5. `JoltConfig.fromEnv()` and `Server.closeOnShutdown()`.

## Consequences

- The public API grows by about ten methods and two small classes.
- No new dependencies. Nothing is enabled unless the app installs it.
- llms.txt stays under 6000 characters.
