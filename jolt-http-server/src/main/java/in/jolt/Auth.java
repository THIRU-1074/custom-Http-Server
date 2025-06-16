package in.jolt;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import org.json.JSONObject;
import java.util.Map;
import java.util.Date;
import java.util.Iterator;
class UnauthorizedException extends RuntimeException{
    public UnauthorizedException(String message) {
        super(message);
    }
}
public class Auth {

private static final String SECRET = "your-secret-key";
    static String basicAuthenticate(String token,JSONObject payloadOut){
        return "";
    }
    public static String createJWT(JSONObject payloadIn) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(SECRET);
            var jwtBuilder = JWT.create();

            // Iterate over JSONObject keys and add claims
            Iterator<String> keys = payloadIn.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = payloadIn.get(key);

                if (value instanceof String) {
                    jwtBuilder.withClaim(key, (String) value);
                } else if (value instanceof Integer) {
                    jwtBuilder.withClaim(key, (Integer) value);
                } else if (value instanceof Boolean) {
                    jwtBuilder.withClaim(key, (Boolean) value);
                } else if (value instanceof Long) {
                    jwtBuilder.withClaim(key, (Long) value);
                } else if (value instanceof Double) {
                    jwtBuilder.withClaim(key, (Double) value);
                } else {
                    jwtBuilder.withClaim(key, value.toString());
                }
            }

            // Optional: add standard JWT fields
            jwtBuilder.withIssuedAt(new Date());
            jwtBuilder.withExpiresAt(new Date(System.currentTimeMillis() + 3600_000));

            return jwtBuilder.sign(algorithm);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
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