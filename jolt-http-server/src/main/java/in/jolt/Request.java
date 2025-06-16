package in.jolt;

import java.util.*;
import org.json.JSONObject;

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
        claim=new JSONObject();
    }
    Content getBody(){
        return body;
    }
boolean authorize(){
        String authType=headers.get("Authorization");
        if(authType==null){
            authFlag="Not Authenticated";
            return false;
        }
        else
        authType=authType.split(" ")[0];
        if(authType.equals("Bearer"))
        authFlag=Auth.AuthenticateJWT(headers.get("Authorization").split(" ")[1],claim);
        else if(authType.equals("Basic"))
        authFlag=Auth.basicAuthenticate(headers.get("Authorization").split(" ")[1],claim);
        System.out.println(authFlag+"-flag");
System.out.println(headers.get("Authorization").split(" ")[1]);
        if(authFlag.equals("Valid"))
        return true;
        else
        return false;
    }
}
