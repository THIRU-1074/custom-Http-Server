package in.jolt.internal.http;

import in.jolt.JoltConfig;

/** Size caps enforced while reading a request. */
public record Limits(
        int maxHeaderBytes, int maxBodyBytes, int maxRequestLineBytes, int maxHeaderCount) {

    /** Takes every cap from {@code config}, the single home of the defaults. */
    public static Limits from(JoltConfig config) {
        return new Limits(
                config.maxHeaderBytes(),
                config.maxBodyBytes(),
                config.maxRequestLineBytes(),
                config.maxHeaderCount());
    }
}
