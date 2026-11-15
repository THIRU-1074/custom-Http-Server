package in.jolt;

/** Thrown from a handler to produce an error response with the given status and message. */
public class HttpException extends RuntimeException {

    private final int status;

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public HttpException(HttpStatus status, String message) {
        this(status.code(), message);
    }

    public int status() {
        return status;
    }
}
