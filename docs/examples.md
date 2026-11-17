# Examples

## attendance

A JWT login demo backed by SQLite. Set `ADMIN_USER` and `ADMIN_PASSWORD` (at least 12 characters) on first start to seed the only user; without them the app starts with no users and logs how to seed one. Passwords are stored as PBKDF2WithHmacSHA256 hashes with a random salt per user. Login throttling is not implemented, so the example is not production-ready.

## webhook

A webhook broadcast service. Set `WEBHOOK_USER` and `WEBHOOK_PASSWORD` before starting; it refuses to start without them. Default port 5008, run with `mvn -pl examples/webhook exec:java`.

## todos-solid

The reference app for [SOLID with Jolt](SOLID.md): domain, adapters and web layers wired by hand in `Main`. Default port 8080 (set `PORT` to change it), run with `mvn -pl examples/todos-solid exec:java`.

The attendance and webhook examples read `JoltConfig.fromEnv()`, so `JOLT_BIND_ADDRESS` changes their bind address (loopback by default) and both take an optional port argument. `todos-solid` uses `JoltConfig.defaults()` and stays on loopback.
