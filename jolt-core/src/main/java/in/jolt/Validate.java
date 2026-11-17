package in.jolt;

import java.util.function.Consumer;

/** Entry points that turn collected {@link Rules} errors into one {@link ValidationException}. */
public final class Validate {

    private Validate() {}

    /**
     * Wraps a typed write handler: runs {@code validator} on the bound body and calls {@code fn}
     * only if it recorded no errors.
     *
     * @throws HttpException 400 if the body is null
     * @throws ValidationException 422 with every field error
     */
    public static <I, O> Typed.Write<I, O> with(Validator<I> validator, Typed.Write<I, O> fn) {
        return (params, body) -> {
            if (body == null) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Body is required");
            }
            check(rules -> validator.validate(body, rules));
            return fn.apply(params, body);
        };
    }

    /**
     * Runs {@code checks} and throws if it recorded any error.
     *
     * @throws ValidationException 422 with every field error
     */
    public static void check(Consumer<Rules> checks) {
        Rules rules = new Rules();
        checks.accept(rules);
        if (!rules.errors().isEmpty()) {
            throw new ValidationException(rules.errors());
        }
    }
}
