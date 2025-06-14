package in.jolt;

import java.util.*;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

class Request {

    Map<String, String> headers;
    String method;
    String url;
    String version;
    Content body;
    JSONObject claim;
    String authFlag;
    Request() {
        headers = new HashMap<>();
        body = null;
        authFlag="";
    }
    Content getBody(){
        return body;
    }
boolean authorize(){
        if(headers.get("Authorization")==null){
            authFlag="Not Authenticated";
        }
        else if(headers.get("Authorization").split(" ")[0].equals("Bearer")){
        authFlag=Auth.AuthenticateJWT(headers.get("Authorization").split(" ")[1],claim);
        if(authFlag.equals("Valid"))
        return true;
        else
        {
        }
        }
        else if(headers.get("Authorization").split(" ")[1].equals("Basic")){

        }
        return false;
    }
}
