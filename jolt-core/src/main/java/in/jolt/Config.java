package in.jolt;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Application settings from environment variables and {@code application.properties}. A key such as
 * {@code db.url} is read from the environment as {@code db.url} or {@code DB_URL}, then from the
 * files; the environment wins. Typed getters fail with a one-line {@link ConfigException} naming
 * the key.
 */
public final class Config {

    private static final Pattern PROFILE = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h|d)");
    private static final String MASK = "****";

    private final Map<String, String> env;
    private final Map<String, String> file;
    private final String profile;

    private Config(Map<String, String> env, Map<String, String> file, String profile) {
        this.env = env;
        this.file = file;
        this.profile = profile;
    }

    /**
     * Loads the process environment and {@code application.properties} from the classpath root and
     * the working directory (the latter wins), then {@code application-<profile>.properties} from
     * the same places. The profile is {@code JOLT_PROFILE} or {@code -Djolt.profile}.
     */
    public static Config load() {
        Map<String, String> env = new HashMap<>(System.getenv());
        String prop = System.getProperty("jolt.profile");
        if (prop != null && !env.containsKey("JOLT_PROFILE")) {
            env.put("JOLT_PROFILE", prop);
        }
        return load(env, Path.of("").toAbsolutePath());
    }

    /** As {@link #load()} over the given environment and directory. */
    public static Config load(Map<String, String> env, Path dir) {
        String profile = env.get("JOLT_PROFILE");
        if (profile != null && profile.isBlank()) {
            profile = null;
        }
        if (profile != null && !PROFILE.matcher(profile).matches()) {
            throw new ConfigException("Config: JOLT_PROFILE must match " + PROFILE.pattern());
        }
        Map<String, String> file = new HashMap<>();
        overlay(file, "application.properties", dir);
        if (profile != null) {
            overlay(file, "application-" + profile + ".properties", dir);
        }
        return new Config(Map.copyOf(env), Map.copyOf(file), profile);
    }

    private static void overlay(Map<String, String> into, String name, Path dir) {
        Properties p = new Properties();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) {
            try (InputStream in = cl.getResourceAsStream(name)) {
                if (in != null) {
                    p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new ConfigException("Config: cannot read " + name + " from the classpath", e);
            }
        }
        Path path = dir.resolve(name);
        if (Files.isRegularFile(path)) {
            try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException e) {
                throw new ConfigException("Config: cannot read " + path, e);
            }
        }
        for (String k : p.stringPropertyNames()) {
            into.put(k, p.getProperty(k));
        }
    }

    /** The active profile, or {@code null}. */
    public String profile() {
        return profile;
    }

    /** Whether {@code key} has a non-blank value. */
    public boolean has(String key) {
        return raw(key) != null;
    }

    /** The value of {@code key}; {@link ConfigException} when missing or blank. */
    public String string(String key) {
        String v = raw(key);
        if (v == null) {
            throw new ConfigException("Config: missing required key '" + key + "'");
        }
        return v;
    }

    /** The value of {@code key}, or {@code fallback} when missing or blank. */
    public String string(String key, String fallback) {
        String v = raw(key);
        return v == null ? fallback : v;
    }

    /** An int; {@link ConfigException} when missing or not a whole number in int range. */
    public int integer(String key) {
        return parseInt(key, string(key));
    }

    public int integer(String key, int fallback) {
        String v = raw(key);
        return v == null ? fallback : parseInt(key, v);
    }

    /**
     * A duration such as {@code 500ms}, {@code 30s}, {@code 5m}, {@code 2h}, {@code 1d} or ISO
     * {@code PT1M30S}.
     */
    public Duration duration(String key) {
        return parseDuration(key, string(key));
    }

    public Duration duration(String key, Duration fallback) {
        String v = raw(key);
        return v == null ? fallback : parseDuration(key, v);
    }

    /**
     * {@code true} or {@code false}, case-insensitive; anything else is a {@link ConfigException}.
     */
    public boolean bool(String key) {
        return parseBool(key, string(key));
    }

    public boolean bool(String key, boolean fallback) {
        String v = raw(key);
        return v == null ? fallback : parseBool(key, v);
    }

    private String raw(String key) {
        String v = env.get(key);
        if (v == null) {
            v = env.get(envName(key));
        }
        if (v == null) {
            v = file.get(key);
        }
        if (v == null || v.isBlank()) {
            return null;
        }
        return v.strip();
    }

    private static String envName(String key) {
        return key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private static int parseInt(String key, String v) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new ConfigException("Config: key '" + key + "' must be an integer");
        }
    }

    private static boolean parseBool(String key, String v) {
        if (v.equalsIgnoreCase("true")) {
            return true;
        }
        if (v.equalsIgnoreCase("false")) {
            return false;
        }
        throw new ConfigException("Config: key '" + key + "' must be true or false");
    }

    private static Duration parseDuration(String key, String v) {
        Matcher m = DURATION.matcher(v);
        try {
            if (m.matches()) {
                long n = Long.parseLong(m.group(1));
                return switch (m.group(2)) {
                    case "ms" -> Duration.ofMillis(n);
                    case "s" -> Duration.ofSeconds(n);
                    case "m" -> Duration.ofMinutes(n);
                    case "h" -> Duration.ofHours(n);
                    default -> Duration.ofDays(n);
                };
            }
            if (v.startsWith("P") || v.startsWith("p")) {
                return Duration.parse(v);
            }
        } catch (DateTimeParseException | ArithmeticException | NumberFormatException e) {
            // reported below with the key
        }
        throw new ConfigException(
                "Config: key '"
                        + key
                        + "' must be a duration such as 500ms, 30s, 5m, 2h, 1d or PT1M30S");
    }

    private static boolean sensitive(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("secret") || k.contains("password") || k.contains("token");
    }

    /**
     * The keys from the files with their effective values, secrets masked. Env-only keys are not
     * listed.
     */
    @Override
    public String toString() {
        Map<String, String> shown = new TreeMap<>();
        for (String k : file.keySet()) {
            String v = env.get(k);
            if (v == null) {
                v = env.get(envName(k));
            }
            shown.put(k, sensitive(k) ? MASK : v != null ? v : file.get(k));
        }
        StringBuilder sb = new StringBuilder("Config[");
        if (profile != null) {
            sb.append("profile=").append(profile).append("; ");
        }
        sb.append(
                String.join(
                        ", ",
                        shown.entrySet().stream()
                                .map(e -> e.getKey() + "=" + e.getValue())
                                .toList()));
        return sb.append(']').toString();
    }
}
