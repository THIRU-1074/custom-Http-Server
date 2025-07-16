package in.jolt;

import java.util.*;
import org.json.JSONObject;

class Request {

    Map<String, String> headers;
    JSONObject cookieJson;
    String method;
    String url;
    String version;
    Content body;
    JSONObject claim;
    String authFlag;
    String path;
    Map<String,String>urlQueryParams;
    Map<String,String>urlPathParams;
    boolean isReady;

    Request() {
        headers = new HashMap<>();
        urlQueryParams=new HashMap<>();
        urlPathParams=new HashMap<>();
        body = null;
        authFlag = "";
        claim = new JSONObject();
        cookieJson = new JSONObject();
        isReady = false;
    }

    Content getBody() {
        return body;
    }

    boolean authorize() {
        String authType = headers.get("Authorization");
        if (authType == null) {
            authFlag = "Not Authenticated";
            return false;
        } else
            authType = authType.split(" ")[0];
        if (authType.equals("Bearer"))
            authFlag = Auth.AuthenticateJWT(headers.get("Authorization").split(" ")[1], claim);
        else if (authType.equals("Basic"))
            authFlag = Auth.basicAuthenticate(headers.get("Authorization").split(" ")[1], claim);
        System.out.println(authFlag + "-flag");
        System.out.println(headers.get("Authorization").split(" ")[1]);
        if (authFlag.equals("Valid"))
            return true;
        else
            return false;
    }
}
