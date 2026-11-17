package in.jolt;

import java.util.function.Consumer;

/** Entry points that turn collected {@link Rules} errors into one {@link ValidationException}. */
public final class Validate {

    private Validate() {}

    /** Validates the bound body, then calls {@code fn}; null body is 400, errors are 422. */
    public static <I, O> Typed.Write<I, O> with(Validator<I> validator, Typed.Write<I, O> fn) {
        return (params, body) -> {
            if (body == null) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Body is required");
            }
            check(rules -> validator.validate(body, rules));
            return fn.apply(params, body);
        };
    }

    /** Runs {@code checks}; throws {@link ValidationException} if any error was recorded. */
    public static void check(Consumer<Rules> checks) {
        Rules rules = new Rules();
        checks.accept(rules);
        if (!rules.errors().isEmpty()) {
            throw new ValidationException(rules.errors());
        }
    }
}
