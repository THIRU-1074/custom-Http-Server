package in.jolt.db;

/** An update touched a different number of rows than expected, so the row changed under us. */
public class OptimisticLockException extends DbException {

    public OptimisticLockException(String message) {
        super(message);
    }
}
