# ADR 0014: jolt-db migrations and booking primitives

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. A ticket-booking app (#55, #56) needs versioned schema changes, generated ids, bulk inserts, a write-locking transaction and a clean way to lose a race for the same row. `jolt-db` must still depend on `java.sql` only and accept bound parameters only.

## Decision
- `Migrations.run(Db, Path)` and `runClasspath(Db, folder)` apply `V<n>__<name>.sql` in numeric order, each in its own transaction, and return the applied `Applied(version, name)` list. `jolt_schema_history` stores version, name, SHA-256 checksum and time. A changed applied file, a duplicate version, an out-of-order file or a misnamed `.sql` file throws a `DbException` naming the file.
- Concurrency: each file runs in an IMMEDIATE transaction whose first statement inserts the history row (primary key on version). A second runner either waits for the lock or fails the insert, re-reads the row and skips the version.
- Statements are split on semicolons outside quotes and comments. Triggers and procedures need a line `-- jolt:delimiter //`; it applies from that line on and `-- jolt:delimiter ;` restores the default. Classpath folders on a jar need directory entries, as Maven jars have.
- `Sql` gains `insert` (generated numeric key as `Optional<Long>`), `batch` (one prepared statement, per-row counts) and the default method `updateExpecting(sql, expectedRows, args...)`, which throws `OptimisticLockException extends DbException` when the count differs. All three exist on `Db` and on the transaction view.
- `Db.batch` runs in one transaction, so it is all or nothing; the overload with a chunk size (default 500) only controls how many rows go per `executeBatch`.
- `Db.inTransaction(TxMode, Tx)`: `DEFERRED` is the old behaviour. `IMMEDIATE` on SQLite runs `BEGIN IMMEDIATE` on an autocommit connection and finishes with `COMMIT` or `ROLLBACK` statements, because the driver issues its own deferred begin when autocommit is turned off. SQLite is detected from the product name; other databases fall back to `DEFERRED`.
- Busy handling retries only the begin, before user code runs, so a retry never repeats side effects. `busyRetries` (default 5, backoff 10, 20, 40, 80, 160 ms) is set on `Db`; when exhausted a `DbException` says the database is busy. Busy is SQLITE_BUSY or SQLITE_LOCKED by error code or message.

## Consequences
- No new dependency; sqlite-jdbc stays test scope. New public types: `Migrations`, `TxMode`, `OptimisticLockException`; `Sql` has three new methods, so custom `Sql` implementations must add `insert` and `batch`.
- Retry does not cover a lock failure after the transaction has begun in DEFERRED mode; use IMMEDIATE for write transactions that must not fail on contention.
- Only SQLite is tested. Other databases get plain `DEFERRED` transactions and whatever generated-key and batch behaviour their driver provides.
- Docs grow by one line in `llms-extras.txt` per feature; `llms.txt` is untouched.
