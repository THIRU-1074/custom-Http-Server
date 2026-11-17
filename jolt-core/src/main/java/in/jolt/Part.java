package in.jolt;

import java.nio.charset.StandardCharsets;

/** One part of a {@code multipart/form-data} body, held in memory. Immutable. */
public final class Part {

    private final String name;
    private final String filename;
    private final String contentType;
    private final byte[] bytes;

    Part(String name, String filename, String contentType, byte[] bytes) {
        this.name = name;
        this.filename = filename;
        this.contentType = contentType;
        this.bytes = bytes;
    }

    /** The form field name. */
    public String name() {
        return name;
    }

    /**
     * The client's file name reduced to its last path segment, without control characters or
     * trailing dots and spaces, cut to 255 characters; {@code null} for a plain field. Empty when
     * nothing usable remains ({@code ..}, a Windows device name such as {@code CON}). Never a path.
     */
    public String filename() {
        return filename;
    }

    /** The part's media type; {@code text/plain} for fields without one. */
    public String contentType() {
        return contentType;
    }

    /** A copy of the content. */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** The content decoded as UTF-8. */
    public String text() {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
