package in.jolt.db;

/** Unchecked wrapper for SQL and pool failures. */
public class DbException extends RuntimeException {

    public DbException(String message) {
        super(message);
    }

    public DbException(String message, Throwable cause) {
        super(message, cause);
    }
}
