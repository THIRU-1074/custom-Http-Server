package in.jolt;

import java.util.LinkedHashMap;
import java.util.Map;

/** Thrown when validation fails: status 422 with every field error, in insertion order. */
public class ValidationException extends HttpException {

    private final Map<String, String> errors;

    public ValidationException(Map<String, String> errors) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed");
        this.errors = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }

    /** Field name to message; read-only. */
    public Map<String, String> errors() {
        return errors;
    }
}
