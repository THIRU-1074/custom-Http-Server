package in.jolt;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.Claim;
import org.json.JSONObject;
import java.util.Map;
import java.util.Date;
import java.util.Iterator;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.sql.*;
import java.util.Base64;

class UnauthorizedException extends RuntimeException{
    public UnauthorizedException(String message) {
        super(message);
    }
}
public class Auth {

private static final String SECRET = "your-secret-key";
    static String basicAuthenticate(String token,JSONObject payloadOut){
        byte[] decodedBytes = Base64.getDecoder().decode(token);
        token = new String(decodedBytes);
        String userId=token.split(":")[0];
        String password=token.split(":")[1];
String sql = "SELECT 1 FROM credentials WHERE user_id = ? AND password = ?";
        Connection conn=null;
        try {
            conn= DBConnection.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, userId);
                stmt.setString(2, password);

                try (ResultSet rs = stmt.executeQuery()) {
                    if(rs.next())
                    return "Valid";
                    else
                    return "InValid";  // true if found
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "DataBase Error";
        }
        finally{
            DBConnection.closeConnection(conn);
        }
    }
    public static JSONObject createJWTPayLoad(String userName,String role,String date,String time){
        JSONObject payload = new JSONObject();

        // Parse input date and time
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d/M/yyyy H:mm");
        LocalDateTime expirationTime = LocalDateTime.parse(date + " " + time, formatter);

        // Current time (issued at)
        long iat = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);

        // Expiration time
long exp = expirationTime.atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond();

        // Build JWT payload
        payload.put("sub", userName);
        payload.put("role", role);
        payload.put("iat", iat);
        payload.put("exp", exp);

        return payload;
    }
    public static JSONObject createJWT(JSONObject payloadIn) {
        JSONObject tokJsonObject=new JSONObject();
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

            tokJsonObject.put("token",jwtBuilder.sign(algorithm));
            return tokJsonObject;

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

        // Extract all claims in a safe way
        Map<String, Claim> claims = jwt.getClaims();
        for (Map.Entry<String, Claim> entry : claims.entrySet()) {
            String key = entry.getKey();
            Claim claim = entry.getValue();

            // Try to infer type: Integer, Double, Boolean, String, Date
            Object value;
            if (claim.asString() != null) {
                value = claim.asString();
            } else if (claim.asInt() != null) {
                value = claim.asInt();
            } else if (claim.asLong() != null) {
                value = claim.asLong();
            } else if (claim.asBoolean() != null) {
                value = claim.asBoolean();
            } else if (claim.asDate() != null) {
                value = claim.asDate().toString();
            } else {
                value = null; // unknown or unsupported type
            }

            if (value != null) {
                payloadOut.put(key, value);
            }
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
    }catch (Exception e) {
        return "Unknown error";
    }
}
}