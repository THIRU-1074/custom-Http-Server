package in.jolt.db;

/** Work done on a single connection inside {@link Db#inTransaction}. */
@FunctionalInterface
public interface Tx<T> {
    T run(Sql sql) throws Exception;
}
