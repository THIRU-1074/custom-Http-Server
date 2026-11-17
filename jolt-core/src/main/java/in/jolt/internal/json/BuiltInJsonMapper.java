package in.jolt.internal.json;

import in.jolt.JsonMapper;
import java.nio.charset.StandardCharsets;

public final class BuiltInJsonMapper implements JsonMapper {

    @Override
    public byte[] write(Object value) {
        return Json.write(value).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <T> T read(byte[] json, Class<T> type) {
        return Json.read(new String(json, StandardCharsets.UTF_8), type);
    }
}
