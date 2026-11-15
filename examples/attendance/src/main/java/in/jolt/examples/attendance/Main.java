package in.jolt.examples.attendance;

import in.jolt.auth.JwtAuth;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.HexFormat;

public final class Main {

    private static final System.Logger LOG = System.getLogger("attendance");

    private Main() {}

    public static void main(String[] args) throws SQLException {
        DBConnection.init();
        String secret = System.getenv("JWT_SECRET");
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            secret = HexFormat.of().formatHex(random);
            LOG.log(
                    System.Logger.Level.WARNING,
                    "JWT_SECRET not set; using a random secret, tokens die on restart");
        }
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5007;
        var server = AttendanceApp.create(JwtAuth.hs256(secret)).listen(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        LOG.log(System.Logger.Level.INFO, "Attendance listening on port " + server.port());
    }
}
