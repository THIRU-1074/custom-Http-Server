# ADR 0014: jolt-db migrations and booking primitives

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. A ticket-booking app (#55, #56) needs versioned schema changes, generated ids, bulk inserts, a write-locking transaction and a clean way to lose a race for the same row. `jolt-db` must still depend on `java.sql` only.

## Decision
- `Migrations.run(Db, Path[, Duration lockTimeout])` and `runClasspath(Db, folder[, Duration])` apply `V<n>__<name>.sql` in numeric order and return the applied `Applied(version, name)` list. The lock timeout defaults to 30 seconds; it is a parameter, not static state.
- `jolt_schema_history(version, name, checksum, success, applied_at)`. Each file runs in one IMMEDIATE transaction that re-reads its row, inserts it with `success=false`, runs the statements and sets `success=true` last. On SQLite and other transactional-DDL databases a failure leaves no row. Where DDL commits implicitly, a half-applied file leaves `success=false`, and every later run stops with "migration V<n> failed earlier and left a partial state; repair manually, then delete the history row". A runner that waited for the lock skips a version only when its row has `success=true` and the same checksum.
- The checksum is SHA-256 of the file text with a UTF-8 BOM removed, CRLF and CR turned into LF and trailing whitespace trimmed, so Windows and Linux checkouts match. A changed applied file, a duplicate version, an out-of-order file or a misnamed `.sql` file throws a `DbException` naming the file.
- Statements split on `;` outside quotes, `$$` and `$tag$` bodies and `--` and `/* */` comments. Backslash escapes and `#` comments are not interpreted; use doubled quotes, or a `-- jolt:delimiter //` line for triggers and procedures.
- Exception to bound parameters: migration files are trusted static SQL, so each statement runs through a plain `Statement.execute`. A literal `?` is text, not a parameter, and a statement may return rows. Application queries still bind every value.
- `runClasspath` reads every classpath root: directories, jars found through the class loader, and jars scanned by entry prefix, so jars without directory entries work. Identical files on two roots count once. Other URL schemes throw `IllegalArgumentException`.
- `Sql` gains `insert`, `batch` and the default `updateExpecting(sql, expectedRows, args...)`, which throws `OptimisticLockException extends DbException`. `insert` returns the first numeric generated-key column, and empty when nothing was inserted (for example a SQLite `insert or ignore` that ignored the row) or no key is numeric. On `Db`, `updateExpecting` runs in its own transaction, so a wrong count changes nothing. Inside a transaction the caller's transaction rolls back.
- `Db.batch` runs in one transaction (all or nothing); the chunk size (default 500) only sets rows per `executeBatch`.
- `Db.inTransaction(TxMode, Tx)`: `DEFERRED` is the old behaviour. `IMMEDIATE` on SQLite runs `BEGIN IMMEDIATE` on an autocommit connection and ends with `COMMIT` or `ROLLBACK` statements, because the driver begins its own deferred transaction when autocommit is turned off. SQLite is detected from the product name; other databases fall back to `DEFERRED`. While the lock is busy (SQLITE_BUSY or SQLITE_LOCKED by code or message) it retries with backoff from 10 ms, capped at 250 ms, up to `busyTimeout` (default 5 s), then throws a `DbException` saying how long it waited. Only the begin is retried, before user code runs, so a retry never repeats side effects.
- The transaction view throws `IllegalStateException` once its transaction has ended.
- A failed statement evicts its connection only when the error looks like a lost connection (SQLState class 08, or `isValid(1)` false), so constraint violations do not churn the pool.

## Consequences
- No new dependency; sqlite-jdbc stays test scope. New public types: `Migrations`, `TxMode`, `OptimisticLockException`. `Sql` has two new abstract methods, so custom `Sql` implementations must add `insert` and `batch`.
- The SQLite driver's own `busy_timeout` (3 s by default in sqlite-jdbc) runs inside each attempt, so the total wait can exceed `busyTimeout` or `lockTimeout`.
- A lock failure after a DEFERRED transaction has begun is not retried; use IMMEDIATE for write transactions that must not fail on contention.
- Only SQLite is tested. Other databases get plain DEFERRED transactions and their driver's generated-key and batch behaviour.
- Scanning jar roots reads each classpath jar's directory once per `runClasspath` call.
- Docs: one line per feature in `llms-extras.txt`; `llms.txt` is untouched.
