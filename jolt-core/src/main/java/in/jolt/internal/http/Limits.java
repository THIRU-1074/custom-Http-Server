package in.jolt.internal.http;

/** Size caps enforced while reading a request. */
public record Limits(int maxHeaderBytes, int maxBodyBytes) {}
