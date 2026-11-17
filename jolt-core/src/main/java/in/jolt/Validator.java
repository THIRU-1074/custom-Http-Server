package in.jolt;

/** Checks a value and records every problem on {@link Rules}; it should not throw for bad input. */
@FunctionalInterface
public interface Validator<T> {

    void validate(T value, Rules rules);

    /** Runs this validator, then {@code next}, collecting errors from both. */
    default Validator<T> and(Validator<T> next) {
        return (value, rules) -> {
            validate(value, rules);
            next.validate(value, rules);
        };
    }
}
