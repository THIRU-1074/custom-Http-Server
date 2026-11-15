package in.jolt.internal.http;

/** Size caps enforced while reading a request. */
public record Limits(
        int maxHeaderBytes, int maxBodyBytes, int maxRequestLineBytes, int maxHeaderCount) {

    public Limits(int maxHeaderBytes, int maxBodyBytes) {
        this(maxHeaderBytes, maxBodyBytes, 8 * 1024, 100);
    }
}
