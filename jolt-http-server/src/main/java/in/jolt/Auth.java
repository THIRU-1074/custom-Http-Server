package in.jolt;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import java.util.Map;
public class Auth {

private static final String SECRET = "your-secret-key";

    static String AuthenticateJWT(String token, JSONObject payloadOut) {
        if (token == null || token.trim().isEmpty()) {
            return "Token is missing";
        }

        try {
            Algorithm algorithm = Algorithm.HMAC256(SECRET);
            JWTVerifier verifier = JWT.require(algorithm).build();
            DecodedJWT jwt = verifier.verify(token);

            // Extract all claims
            Map<String, Object> claims = jwt.getClaims().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().as(Object.class)
                    ));

            // Fill the provided JSONObject with claims
            for (Map.Entry<String, Object> entry : claims.entrySet()) {
                payloadOut.put(entry.getKey(), entry.getValue());
            }

            return "Valid";
        } catch (TokenExpiredException e) {
            return "Token expired";
        } catch (SignatureVerificationException e) {
            return "Invalid signature";
        } catch (JWTDecodeException e) {
            return "Invalid format";
        } catch (JWTVerificationException e) {
            return "Verification failed";
        } catch (Exception e) {
            return "Unknown error";
        }
    }
}