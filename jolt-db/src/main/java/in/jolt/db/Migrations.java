package in.jolt.db;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies {@code V<number>__<name>.sql} files in numeric order, each in its own transaction, and
 * records them in {@code jolt_schema_history}. A file may hold several statements separated by
 * semicolons outside quotes, {@code $$} or {@code $tag$} bodies and comments; a line {@code --
 * jolt:delimiter //} changes the separator, which triggers and procedures need. Backslash escapes
 * and {@code #} comments are not interpreted: double the quote instead. Migration files are trusted
 * static SQL, so each statement runs as a plain {@code Statement} and a literal {@code ?} is not a
 * parameter; this is the one place jolt-db does not bind parameters.
 */
public final class Migrations {

    /** A migration applied by this run. */
    public record Applied(long version, String name) {}

    private record Source(String file, byte[] bytes) {}

    private record Script(long version, String name, String file, String sql, String checksum) {}

    private static final Pattern FILE = Pattern.compile("V(\\d{1,18})__(.+)\\.sql");
    private static final String DELIMITER = "-- jolt:delimiter";
    private static final Pattern DOLLAR = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)?\\$");

    /** How long a run waits for another runner's lock, 30 seconds. */
    public static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(30);

    private Migrations() {}

    /** Applies the pending migrations found in {@code dir} and returns them in order. */
    public static List<Applied> run(Db db, Path dir) {
        return run(db, dir, DEFAULT_LOCK_TIMEOUT);
    }

    /** Like {@link #run(Db, Path)}, waiting up to {@code lockTimeout} for another runner. */
    public static List<Applied> run(Db db, Path dir, Duration lockTimeout) {
        List<Source> sources = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                if (Files.isRegularFile(p)) {
                    sources.add(new Source(p.getFileName().toString(), Files.readAllBytes(p)));
                }
            }
        } catch (IOException e) {
            throw new DbException("cannot read migrations in " + dir + ": " + e.getMessage(), e);
        }
        return apply(db, sources, lockTimeout);
    }

    /** Applies the pending migrations in a classpath folder, directory or jar. */
    public static List<Applied> runClasspath(Db db, String folder) {
        return runClasspath(db, folder, DEFAULT_LOCK_TIMEOUT);
    }

    /** Like {@link #runClasspath(Db, String)}, waiting up to {@code lockTimeout}. */
    public static List<Applied> runClasspath(Db db, String folder, Duration lockTimeout) {
        String base = folder.replaceAll("^/+|/+$", "");
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = Migrations.class.getClassLoader();
        List<Source> sources = new ArrayList<>();
        Set<Path> jars = new HashSet<>();
        try {
            var urls = loader.getResources(base);
            while (urls.hasMoreElements()) readUrl(urls.nextElement(), base, sources, jars);
            for (Path jar : jarRoots(loader)) {
                if (jars.add(jar)) readJar(jar, base, sources);
            }
        } catch (IOException | URISyntaxException e) {
            throw new DbException("cannot read migrations in " + folder + ": " + e.getMessage(), e);
        }
        if (sources.isEmpty()) {
            throw new DbException("no migrations on the classpath in " + folder);
        }
        return apply(db, sources, lockTimeout);
    }

    private static void readUrl(URL url, String base, List<Source> out, Set<Path> jars)
            throws IOException, URISyntaxException {
        switch (url.getProtocol()) {
            case "jar" -> {
                JarURLConnection conn = (JarURLConnection) url.openConnection();
                URL file = conn.getJarFileURL();
                if (!file.getProtocol().equals("file")) {
                    throw new IllegalArgumentException(
                            "unsupported classpath scheme: " + file.getProtocol());
                }
                Path jar = Path.of(file.toURI());
                if (jars.add(jar)) readJar(jar, base, out);
            }
            case "file" -> {
                try (var files = Files.list(Path.of(url.toURI()))) {
                    for (Path p : files.toList()) {
                        if (Files.isRegularFile(p)) {
                            out.add(new Source(p.getFileName().toString(), Files.readAllBytes(p)));
                        }
                    }
                }
            }
            default ->
                    throw new IllegalArgumentException(
                            "unsupported classpath scheme: " + url.getProtocol());
        }
    }

    private static void readJar(Path path, String base, List<Source> out) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.startsWith(base + "/")) continue;
                String rest = n.substring(base.length() + 1);
                if (rest.indexOf('/') >= 0) continue;
                try (InputStream in = jar.getInputStream(e)) {
                    out.add(new Source(rest, in.readAllBytes()));
                }
            }
        }
    }

    private static Set<Path> jarRoots(ClassLoader loader) throws URISyntaxException {
        Set<Path> roots = new LinkedHashSet<>();
        for (ClassLoader cl = loader; cl != null; cl = cl.getParent()) {
            if (cl instanceof URLClassLoader u) {
                for (URL url : u.getURLs()) {
                    if (url.getProtocol().equals("file")) roots.add(Path.of(url.toURI()));
                }
            }
            if (cl == ClassLoader.getSystemClassLoader()) {
                for (String entry :
                        System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                    if (!entry.isEmpty()) roots.add(Path.of(entry).toAbsolutePath());
                }
            }
        }
        roots.removeIf(p -> !p.toString().endsWith(".jar") || !Files.isRegularFile(p));
        return roots;
    }

    private record Row(long version, String checksum, boolean success) {}

    private static List<Applied> apply(Db db, List<Source> sources, Duration lockTimeout) {
        List<Script> scripts = parse(sources);
        db.update(
                "create table if not exists jolt_schema_history(version bigint primary key,"
                        + " name varchar(255) not null, checksum varchar(64) not null,"
                        + " success boolean not null, applied_at varchar(40) not null)");
        TreeMap<Long, Row> applied = new TreeMap<>();
        for (Row row :
                db.query("select version, checksum, success from jolt_schema_history", ROW)) {
            if (!row.success()) throw partial(row.version());
            applied.put(row.version(), row);
        }
        long latest = applied.isEmpty() ? -1 : applied.lastKey();
        List<Applied> done = new ArrayList<>();
        for (Script s : scripts) {
            Row known = applied.get(s.version());
            if (known != null) {
                if (!known.checksum().equals(s.checksum())) throw changed(s);
                continue;
            }
            if (s.version() < latest) {
                throw new DbException(
                        "migration "
                                + s.file()
                                + " is out of order: version "
                                + latest
                                + " is already applied");
            }
            if (applyOne(db, s, lockTimeout)) done.add(new Applied(s.version(), s.name()));
        }
        return done;
    }

    private static final RowMapper<Row> ROW =
            rs -> new Row(rs.getLong(1), rs.getString(2), rs.getBoolean(3));

    private static boolean applyOne(Db db, Script s, Duration lockTimeout) {
        return db.transact(
                TxMode.IMMEDIATE,
                lockTimeout,
                c -> {
                    Optional<Row> other =
                            Jdbc.queryOne(
                                    c,
                                    0,
                                    "select version, checksum, success from jolt_schema_history"
                                            + " where version = ?",
                                    ROW,
                                    new Object[] {s.version()});
                    if (other.isPresent()) {
                        if (!other.get().success()) throw partial(s.version());
                        if (!other.get().checksum().equals(s.checksum())) throw changed(s);
                        return false;
                    }
                    Jdbc.update(
                            c,
                            0,
                            "insert into jolt_schema_history(version, name, checksum, success,"
                                    + " applied_at) values (?, ?, ?, ?, ?)",
                            new Object[] {
                                s.version(), s.name(), s.checksum(), false, Instant.now().toString()
                            });
                    try {
                        for (String statement : split(s.sql())) Jdbc.execute(c, statement);
                    } catch (SQLException e) {
                        throw new DbException(
                                "migration " + s.file() + " failed: " + e.getMessage(), e);
                    }
                    Jdbc.update(
                            c,
                            0,
                            "update jolt_schema_history set success = ? where version = ?",
                            new Object[] {true, s.version()});
                    return true;
                });
    }

    private static DbException partial(long version) {
        return new DbException(
                "migration V"
                        + version
                        + " failed earlier and left a partial state; repair manually, then delete"
                        + " the history row");
    }

    private static DbException changed(Script s) {
        return new DbException(
                "migration "
                        + s.file()
                        + " changed after it was applied; add a new version instead");
    }

    private static List<Script> parse(List<Source> sources) {
        TreeMap<Long, Script> byVersion = new TreeMap<>();
        for (Source src : sources) {
            if (!src.file().endsWith(".sql")) continue;
            Matcher m = FILE.matcher(src.file());
            if (!m.matches()) {
                throw new DbException(
                        "bad migration name " + src.file() + ", expected V<number>__<name>.sql");
            }
            long version = Long.parseLong(m.group(1));
            String text = normalize(new String(src.bytes(), StandardCharsets.UTF_8));
            Script script = new Script(version, m.group(2), src.file(), text, checksum(text));
            Script clash = byVersion.putIfAbsent(version, script);
            if (clash != null
                    && !(clash.name().equals(script.name())
                            && clash.checksum().equals(script.checksum()))) {
                throw new DbException(
                        "duplicate version "
                                + version
                                + ": "
                                + clash.file()
                                + " and "
                                + src.file());
            }
        }
        return new ArrayList<>(byVersion.values());
    }

    static String normalize(String text) {
        String s = text.startsWith("﻿") ? text.substring(1) : text;
        return s.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
    }

    static String checksum(String text) {
        try {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<String> split(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String delimiter = ";";
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            if ((i == 0 || sql.charAt(i - 1) == '\n') && sql.startsWith(DELIMITER, i)) {
                int eol = sql.indexOf('\n', i);
                if (eol < 0) eol = n;
                String d = sql.substring(i + DELIMITER.length(), eol).strip();
                delimiter = d.isEmpty() ? ";" : d;
                i = eol;
            } else if (ch == '\'' || ch == '"' || ch == '`') {
                int end = sql.indexOf(ch, i + 1);
                end = end < 0 ? n : end + 1;
                cur.append(sql, i, end);
                i = end;
            } else if (ch == '$' && dollarEnd(sql, i) > 0) {
                int end = dollarEnd(sql, i);
                cur.append(sql, i, end);
                i = end;
            } else if (sql.startsWith("--", i)) {
                int eol = sql.indexOf('\n', i);
                i = eol < 0 ? n : eol;
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                cur.append(' ');
            } else if (sql.startsWith(delimiter, i)) {
                flush(cur, out);
                i += delimiter.length();
            } else {
                cur.append(ch);
                i++;
            }
        }
        flush(cur, out);
        return out;
    }

    private static int dollarEnd(String sql, int i) {
        Matcher m = DOLLAR.matcher(sql).region(i, sql.length());
        if (!m.lookingAt()) return -1;
        String tag = m.group();
        int close = sql.indexOf(tag, m.end());
        return close < 0 ? -1 : close + tag.length();
    }

    private static void flush(StringBuilder cur, List<String> out) {
        String statement = cur.toString().strip();
        if (!statement.isEmpty()) out.add(statement);
        cur.setLength(0);
    }
}
