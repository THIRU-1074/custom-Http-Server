package in.jolt.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.db.Migrations.Applied;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationsTest {

    @TempDir Path dir;
    Path sql;
    Db db;

    @BeforeEach
    void open() throws IOException {
        sql = Files.createDirectory(dir.resolve("sql"));
        db = Db.open("jdbc:sqlite:" + dir.resolve("m.db") + "?busy_timeout=10000");
    }

    @AfterEach
    void close() {
        db.close();
    }

    void file(String name, String body) throws IOException {
        Files.writeString(sql.resolve(name), body);
    }

    int count(String table) {
        return db.queryOne("select count(*) from " + table, rs -> rs.getInt(1)).orElseThrow();
    }

    @Test
    void appliesInNumericOrderAndRecordsHistory() throws IOException {
        file("V10__ten.sql", "create table ten(id integer);");
        file("V2__two.sql", "create table two(id integer); insert into two values (1);");
        file("notes.txt", "ignored");

        List<Applied> done = Migrations.run(db, sql);

        assertEquals(List.of(new Applied(2, "two"), new Applied(10, "ten")), done);
        assertEquals(1, count("two"));
        var rows =
                db.query(
                        "select version, name, checksum, applied_at from jolt_schema_history"
                                + " order by version",
                        rs -> rs.getLong(1) + rs.getString(2) + rs.getString(3).length());
        assertEquals(List.of("2two64", "10ten64"), rows);
        assertEquals(0, db.inUse());
    }

    @Test
    void secondRunAppliesNothingAndNewFilesAreAdded() throws IOException {
        file("V1__a.sql", "create table a(id integer);");
        assertEquals(1, Migrations.run(db, sql).size());
        assertEquals(List.of(), Migrations.run(db, sql));
        file("V2__b.sql", "create table b(id integer);");
        assertEquals(List.of(new Applied(2, "b")), Migrations.run(db, sql));
    }

    @Test
    void splitsOnSemicolonsOutsideQuotesAndComments() throws IOException {
        file(
                "V1__x.sql",
                "create table t(v text); -- one; two\n"
                        + "insert into t values ('a;b');\n"
                        + "/* skip; this */ insert into t values (\"c;d\");\n"
                        + "insert into t values ('it''s');;\n"
                        + "-- trailing");
        Migrations.run(db, sql);
        assertEquals(
                List.of("a;b", "c;d", "it's"),
                db.query("select v from t order by rowid", rs -> rs.getString(1)));
    }

    @Test
    void customDelimiterAllowsTriggers() throws IOException {
        file(
                "V1__trigger.sql",
                "create table a(id integer);\n"
                        + "create table log(n integer);\n"
                        + "-- jolt:delimiter //\n"
                        + "create trigger t after insert on a begin\n"
                        + "  insert into log values (new.id);\n"
                        + "end//\n"
                        + "-- jolt:delimiter ;\n"
                        + "insert into a values (7);\n");
        Migrations.run(db, sql);
        assertEquals(7, db.queryOne("select n from log", rs -> rs.getInt(1)).orElseThrow());
    }

    @Test
    void changedAppliedFileFailsNamingIt() throws IOException {
        file("V1__a.sql", "create table a(id integer);");
        Migrations.run(db, sql);
        file("V1__a.sql", "create table a(id integer, x text);");
        var e = assertThrows(DbException.class, () -> Migrations.run(db, sql));
        assertTrue(e.getMessage().contains("V1__a.sql"), e.getMessage());
        assertTrue(e.getMessage().contains("changed"), e.getMessage());
    }

    @Test
    void duplicateVersionFails() throws IOException {
        file("V1__a.sql", "create table a(id integer);");
        file("V01__b.sql", "create table b(id integer);");
        var e = assertThrows(DbException.class, () -> Migrations.run(db, sql));
        assertTrue(e.getMessage().contains("duplicate version 1"), e.getMessage());
        assertTrue(e.getMessage().contains("V1__a.sql"), e.getMessage());
        assertTrue(e.getMessage().contains("V01__b.sql"), e.getMessage());
    }

    @Test
    void outOfOrderFileFails() throws IOException {
        file("V1__a.sql", "create table a(id integer);");
        file("V3__c.sql", "create table c(id integer);");
        Migrations.run(db, sql);
        file("V2__b.sql", "create table b(id integer);");
        var e = assertThrows(DbException.class, () -> Migrations.run(db, sql));
        assertTrue(e.getMessage().contains("V2__b.sql"), e.getMessage());
        assertTrue(e.getMessage().contains("out of order"), e.getMessage());
    }

    @Test
    void badNameFails() throws IOException {
        file("V1_a.sql", "create table a(id integer);");
        var e = assertThrows(DbException.class, () -> Migrations.run(db, sql));
        assertTrue(e.getMessage().contains("V1_a.sql"), e.getMessage());
    }

    @Test
    void failingFileRollsBackAndNamesItself() throws IOException {
        file("V1__a.sql", "create table a(id integer);");
        file("V2__b.sql", "create table b(id integer); insert into nope values (1);");
        var e = assertThrows(DbException.class, () -> Migrations.run(db, sql));
        assertTrue(e.getMessage().contains("V2__b.sql"), e.getMessage());
        assertEquals(1, count("jolt_schema_history"));
        assertEquals(
                0, db.query("select name from sqlite_master where name = 'b'", rs -> 1).size());
        assertEquals(0, db.inUse());
    }

    @Test
    void missingDirectoryFails() {
        assertThrows(DbException.class, () -> Migrations.run(db, dir.resolve("missing")));
    }

    @Test
    void appliesFromClasspathFolder() {
        List<Applied> done = Migrations.runClasspath(db, "db/migrations");
        assertEquals(List.of(new Applied(1, "movie"), new Applied(2, "seat")), done);
        assertEquals(1, count("movie"));
        assertEquals(List.of(), Migrations.runClasspath(db, "/db/migrations/"));
    }

    @Test
    void missingClasspathFolderFails() {
        assertThrows(DbException.class, () -> Migrations.runClasspath(db, "db/nothing"));
    }

    @Test
    void appliesFromJar() throws Exception {
        Path jar = dir.resolve("m.jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out)) {
            jos.putNextEntry(new JarEntry("mig/"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("mig/V1__j.sql"));
            jos.write("create table j(id integer);".getBytes());
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("mig/deeper/V9__no.sql"));
            jos.write("create table no(id integer);".getBytes());
            jos.closeEntry();
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader cl = new URLClassLoader(new URL[] {jar.toUri().toURL()}, previous)) {
            Thread.currentThread().setContextClassLoader(cl);
            assertEquals(List.of(new Applied(1, "j")), Migrations.runClasspath(db, "mig"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void concurrentStartsApplyEachVersionOnce() throws Exception {
        file("V1__a.sql", "create table a(id integer); create table counter(n integer);");
        file("V2__b.sql", "insert into counter values (1);");
        file("V3__c.sql", "insert into counter values (1);");
        String url = "jdbc:sqlite:" + dir.resolve("m.db") + "?busy_timeout=10000";
        CyclicBarrier start = new CyclicBarrier(2);
        List<Db> dbs =
                List.of(
                        Db.open(url, 2, Duration.ofSeconds(5)),
                        Db.open(url, 2, Duration.ofSeconds(5)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<Applied>>> runs = new ArrayList<>();
            for (Db d : dbs) {
                runs.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return Migrations.run(d, sql);
                                }));
            }
            int applied = 0;
            for (var f : runs) applied += f.get().size();
            assertEquals(3, applied);
            assertEquals(3, count("jolt_schema_history"));
            assertEquals(2, count("counter"));
        } finally {
            pool.shutdownNow();
            dbs.forEach(Db::close);
        }
    }
}
