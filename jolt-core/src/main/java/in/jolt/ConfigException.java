package in.jolt;

/**
 * A required setting is missing or a value is malformed. The message names the key, never the
 * value.
 */
public final class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
