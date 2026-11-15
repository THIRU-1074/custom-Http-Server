package in.jolt.examples.webhook;

import in.jolt.JoltConfig;
import java.sql.SQLException;

public final class Main {

    private static final System.Logger LOG = System.getLogger("webhook");

    private Main() {}

    public static void main(String[] args) throws SQLException {
        String user = System.getenv("WEBHOOK_USER");
        String password = System.getenv("WEBHOOK_PASSWORD");
        if (user == null || password == null) {
            throw new IllegalStateException("Set WEBHOOK_USER and WEBHOOK_PASSWORD");
        }
        DBConnection.init();
        var config = JoltConfig.fromEnv();
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5008;
        var server =
                NotificationServer.create((u, p) -> u.equals(user) && p.equals(password), config)
                        .listen(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        LOG.log(System.Logger.Level.INFO, "Webhook listening on port " + server.port());
    }
}
