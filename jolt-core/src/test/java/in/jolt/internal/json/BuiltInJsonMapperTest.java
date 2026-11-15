package in.jolt.internal.json;

import in.jolt.JsonMapper;
import in.jolt.JsonMapperContract;

class BuiltInJsonMapperTest extends JsonMapperContract {

    @Override
    protected JsonMapper mapper() {
        return new BuiltInJsonMapper();
    }
}
