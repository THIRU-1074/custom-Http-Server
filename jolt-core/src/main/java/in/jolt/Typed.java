package in.jolt;

/** Handler shapes for typed routes. They see {@link Params}, never Request or Response. */
public interface Typed {

    /** Reads params and returns the result: a value, a {@link Reply}, or {@code null} for 204. */
    @FunctionalInterface
    interface Read<O> {
        O apply(Params params) throws Exception;
    }

    /** Takes the bound JSON body and params, and returns the result like {@link Read}. */
    @FunctionalInterface
    interface Write<I, O> {
        O apply(Params params, I body) throws Exception;
    }

    /** Takes params and returns nothing; the route answers 204. */
    @FunctionalInterface
    interface Run {
        void run(Params params) throws Exception;
    }
}
