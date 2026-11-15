package in.jolt.examples.attendance;

import in.jolt.JoltConfig;
import in.jolt.auth.JwtAuth;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.HexFormat;

public final class Main {

    private static final System.Logger LOG = System.getLogger("attendance");

    private static final int MIN_SECRET_BYTES = 32;

    private Main() {}

    public static void main(String[] args) throws SQLException {
        String secret;
        try {
            secret = resolveSecret(System.getenv("JWT_SECRET"));
        } catch (IllegalArgumentException e) {
            LOG.log(System.Logger.Level.ERROR, e.getMessage());
            System.exit(1);
            return;
        }
        DBConnection.init();
        var config = JoltConfig.fromEnv();
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5007;
        var server = AttendanceApp.create(JwtAuth.hs256(secret), config).listen(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        LOG.log(System.Logger.Level.INFO, "Attendance listening on port " + server.port());
    }

    /**
     * Returns the secret to sign tokens with: {@code configured} if it is at least 32 bytes, a
     * random one if it is missing or blank.
     *
     * @throws IllegalArgumentException if {@code configured} is set but shorter than 32 bytes
     */
    static String resolveSecret(String configured) {
        if (configured == null || configured.isBlank()) {
            byte[] random = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(random);
            LOG.log(
                    System.Logger.Level.WARNING,
                    "JWT_SECRET not set; using a random secret, tokens die on restart");
            return HexFormat.of().formatHex(random);
        }
        if (configured.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes long");
        }
        return configured;
    }
}
