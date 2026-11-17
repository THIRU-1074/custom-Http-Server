package in.jolt.db;

/** How {@link Db#inTransaction(TxMode, Tx)} begins a transaction. */
public enum TxMode {
    /** The driver default: locks are taken on first use. */
    DEFERRED,
    /** Takes the write lock at begin (SQLite BEGIN IMMEDIATE); other databases use DEFERRED. */
    IMMEDIATE
}
