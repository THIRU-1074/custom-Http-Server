package in.jolt;

import java.util.*;

class Request {

    Map<String, String> headers;
    String method;
    String url;
    String version;
    Content body;

    Request() {
        headers = new HashMap<>();
        body = null;
    }
    Content getBody(){
        return body;
    }
}
