package in.jolt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.test.JoltTest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MultipartTest {

    private static final String B = "XyZ-boundary";

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] body(byte[]... pieces) {
        var out = new ByteArrayOutputStream();
        for (byte[] p : pieces) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] part(String disposition, String type, byte[] content) {
        String head =
                "--"
                        + B
                        + "\r\nContent-Disposition: "
                        + disposition
                        + "\r\n"
                        + (type == null ? "" : "Content-Type: " + type + "\r\n")
                        + "\r\n";
        return body(ascii(head), content, ascii("\r\n"));
    }

    private static byte[] field(String name, String value) {
        return part("form-data; name=\"" + name + "\"", null, ascii(value));
    }

    private static byte[] close() {
        return ascii("--" + B + "--\r\n");
    }

    private static List<Part> parse(JoltConfig config, String contentType, byte[] payload) {
        App app = Jolt.create(config);
        var holder =
                new Object() {
                    List<Part> parts;
                };
        app.post(
                "/u",
                (req, res) -> {
                    holder.parts = req.parts();
                    res.status(204).send(new byte[0]);
                });
        var call = JoltTest.of(app).post("/u").body(payload);
        if (contentType != null) {
            call.header("Content-Type", contentType);
        }
        call.expect(204);
        return holder.parts;
    }

    private static List<Part> parse(byte[] payload) {
        return parse(JoltConfig.defaults(), "multipart/form-data; boundary=" + B, payload);
    }

    private static int status(JoltConfig config, String contentType, byte[] payload) {
        App app = Jolt.create(config);
        app.post("/u", (req, res) -> res.text(String.valueOf(req.parts().size())));
        var call = JoltTest.of(app).post("/u").body(payload);
        if (contentType != null) {
            call.header("Content-Type", contentType);
        }
        return call.send().status();
    }

    private static int status(byte[] payload) {
        return status(JoltConfig.defaults(), "multipart/form-data; boundary=" + B, payload);
    }

    @Test
    void parsesFieldsAndAFile() {
        List<Part> parts =
                parse(
                        body(
                                field("title", "Hello"),
                                part(
                                        "form-data; name=\"doc\"; filename=\"a.txt\"",
                                        "text/plain",
                                        ascii("file body")),
                                field("note", "caf\u00e9"),
                                close()));
        assertEquals(3, parts.size());
        assertEquals("title", parts.get(0).name());
        assertNull(parts.get(0).filename());
        assertEquals("Hello", parts.get(0).text());
        assertEquals("doc", parts.get(1).name());
        assertEquals("a.txt", parts.get(1).filename());
        assertEquals("text/plain", parts.get(1).contentType());
        assertEquals("file body", parts.get(1).text());
        assertEquals("caf\u00e9", parts.get(2).text());
    }

    @Test
    void binaryFileSurvivesIncludingCrLfAndDashes() {
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        byte[] tricky = body(data, ascii("\r\n--almost\r\n--"), data);
        List<Part> parts =
                parse(
                        body(
                                part(
                                        "form-data; name=\"f\"; filename=\"b.bin\"",
                                        "application/octet-stream",
                                        tricky),
                                close()));
        assertArrayEquals(tricky, parts.get(0).bytes());
    }

    @Test
    void emptyContentAndDuplicateNamesAndDefaultType() {
        List<Part> parts = parse(body(field("a", ""), field("a", "2"), close()));
        assertEquals("", parts.get(0).text());
        assertEquals("2", parts.get(1).text());
        assertEquals("text/plain", parts.get(0).contentType());
    }

    @Test
    void preambleEpilogueQuotedBoundaryAndMediaTypeCaseAreAccepted() {
        byte[] payload =
                body(ascii("ignored preamble\r\n"), field("a", "1"), close(), ascii("tail"));
        assertEquals(
                "1",
                parse(JoltConfig.defaults(), "Multipart/Form-Data; boundary=\"" + B + "\"", payload)
                        .get(0)
                        .text());
    }

    @Test
    void emptyFormHasNoParts() {
        assertEquals(0, parse(close()).size());
    }

    @Test
    void partsAreImmutableAndBytesAreCopied() {
        List<Part> parts = parse(body(field("a", "x"), close()));
        assertThrows(UnsupportedOperationException.class, () -> parts.add(parts.get(0)));
        Part p = parts.get(0);
        p.bytes()[0] = 'Z';
        assertEquals("x", p.text());
        assertNotSame(p.bytes(), p.bytes());
    }

    @Test
    void escapedQuoteInNameIsUnescaped() {
        List<Part> parts =
                parse(body(part("form-data; name=\"a\\\"b\"", null, ascii("v")), close()));
        assertEquals("a\"b", parts.get(0).name());
    }

    @Test
    void filenamesCannotTraversePaths() {
        String[][] cases = {
            {"../../etc/passwd", "passwd"},
            {"..\\..\\win.ini", "win.ini"},
            {"C:\\Users\\me\\y.txt", "y.txt"},
            {"/abs/path/z.png", "z.png"},
            {"a/b/c.png", "c.png"},
            {"..", ""},
            {".", ""},
            {"../", ""},
            {"ta\tb.txt", "tab.txt"},
            {"report.pdf", "report.pdf"},
        };
        for (String[] c : cases) {
            List<Part> parts =
                    parse(
                            body(
                                    part(
                                            "form-data; name=\"f\"; filename=\"" + c[0] + "\"",
                                            null,
                                            ascii("x")),
                                    close()));
            assertEquals(c[1], parts.get(0).filename(), c[0]);
        }
    }

    private static String filename(String raw) {
        return parse(
                        body(
                                part(
                                        "form-data; name=\"f\"; filename=\"" + raw + "\"",
                                        null,
                                        ascii("x")),
                                close()))
                .get(0)
                .filename();
    }

    @Test
    void browserWindowsPathEndingInABackslashStillParses() {
        assertEquals("", filename("C:\\uploads\\"));
        assertEquals("a.txt", filename("C:\\uploads\\a.txt"));
        List<Part> parts =
                parse(
                        body(
                                part(
                                        "form-data; name=\"f\"; filename=\"C:\\uploads\\\"",
                                        null,
                                        ascii("x")),
                                field("after", "ok"),
                                close()));
        assertEquals("after", parts.get(1).name());
    }

    @Test
    void trailingDotsAndSpacesAreStripped() {
        assertEquals("report", filename("report. . "));
        assertEquals("", filename("..."));
        assertEquals("", filename("   "));
        assertEquals("a.txt", filename("a.txt"));
    }

    @Test
    void windowsDeviceNamesAreEmpty() {
        for (String n :
                new String[] {
                    "CON",
                    "con",
                    "PRN",
                    "Aux",
                    "NUL",
                    "COM1",
                    "com9",
                    "LPT1",
                    "lpt9",
                    "con.txt",
                    "NUL.tar.gz",
                    "AUX .txt",
                    "COM3.",
                    "dir/CON.log"
                }) {
            assertEquals("", filename(n), n);
        }
        for (String n : new String[] {"COM0", "COM10", "LPT", "console.txt", "xcon", "nul-1"}) {
            assertEquals(n, filename(n), n);
        }
    }

    @Test
    void truncationNeverSplitsASurrogatePair() {
        String emoji = new String(Character.toChars(0x1F600));
        String got = filename("a".repeat(254) + emoji);
        assertEquals(254, got.length());
        assertEquals(255, filename("b".repeat(300)).length());
        String cut = filename(emoji.repeat(200));
        assertEquals(0, cut.length() % 2);
        assertEquals(true, cut.length() <= 255);
    }

    @Test
    void overlongFilenameIsTruncated() {
        List<Part> parts =
                parse(
                        body(
                                part(
                                        "form-data; name=\"f\"; filename=\""
                                                + "a".repeat(500)
                                                + "\"",
                                        null,
                                        ascii("x")),
                                close()));
        assertEquals(255, parts.get(0).filename().length());
    }

    @Test
    void partOverTheLimitGets413() {
        JoltConfig cfg = JoltConfig.defaults().maxPartBytes(8);
        byte[] ok =
                body(
                        part("form-data; name=\"f\"; filename=\"a\"", null, ascii("12345678")),
                        close());
        byte[] big =
                body(
                        part("form-data; name=\"f\"; filename=\"a\"", null, ascii("123456789")),
                        close());
        assertEquals(200, status(cfg, "multipart/form-data; boundary=" + B, ok));
        assertEquals(413, status(cfg, "multipart/form-data; boundary=" + B, big));
    }

    @Test
    void tooManyPartsGets413() {
        JoltConfig cfg = JoltConfig.defaults().maxParts(2);
        String ct = "multipart/form-data; boundary=" + B;
        assertEquals(200, status(cfg, ct, body(field("a", "1"), field("b", "2"), close())));
        assertEquals(
                413,
                status(cfg, ct, body(field("a", "1"), field("b", "2"), field("c", "3"), close())));
    }

    @Test
    void defaultsAreTenPartsAndOneMegabyte() {
        assertEquals(10, JoltConfig.defaults().maxParts());
        assertEquals(1024 * 1024, JoltConfig.defaults().maxPartBytes());
    }

    @Test
    void bodyOverMaxBodyBytesGets413() {
        JoltConfig cfg = JoltConfig.defaults().maxBodyBytes(40);
        assertEquals(
                413,
                status(
                        cfg,
                        "multipart/form-data; boundary=" + B,
                        body(field("a", "x".repeat(100)), close())));
    }

    @Test
    void otherContentTypesGet415() {
        byte[] payload = body(field("a", "1"), close());
        assertEquals(415, status(JoltConfig.defaults(), "application/json", payload));
        assertEquals(415, status(JoltConfig.defaults(), null, payload));
        assertEquals(
                415, status(JoltConfig.defaults(), "application/x-www-form-urlencoded", payload));
    }

    @Test
    void badBoundaryGets400() {
        byte[] payload = body(field("a", "1"), close());
        for (String ct :
                new String[] {
                    "multipart/form-data",
                    "multipart/form-data; boundary=",
                    "multipart/form-data; boundary=\"\"",
                    "multipart/form-data; boundary=" + "b".repeat(71),
                    "multipart/form-data; boundary=a\"b",
                    "multipart/form-data; boundary=a>b"
                }) {
            assertEquals(400, status(JoltConfig.defaults(), ct, payload), ct);
        }
    }

    @Test
    void malformedBodiesGet400() {
        String head = "--" + B + "\r\n";
        String disp = "Content-Disposition: form-data; name=\"a\"\r\n";
        String[] bad = {
            "",
            "garbage",
            head + disp + "\r\n" + "v\r\n",
            head + disp + "\r\nv\r\n--" + B,
            head + disp + "\r\nv\r\n--" + B + "x\r\n",
            head + "\r\nv\r\n--" + B + "--\r\n",
            head + "Content-Type: text/plain\r\n\r\nv\r\n--" + B + "--\r\n",
            head + "Content-Disposition: attachment; name=\"a\"\r\n\r\nv\r\n--" + B + "--\r\n",
            head + "Content-Disposition: form-data\r\n\r\nv\r\n--" + B + "--\r\n",
            head + "Content-Disposition: form-data; name=a b\r\n\r\nv\r\n--" + B + "--\r\n",
            head + "Content-Disposition: form-data; name=\"a\r\n\r\nv\r\n--" + B + "--\r\n",
            head + "no colon here\r\n\r\nv\r\n--" + B + "--\r\n",
            head + " folded: x\r\n" + disp + "\r\nv\r\n--" + B + "--\r\n",
            head + disp + "\rbare\r\n\r\nv\r\n--" + B + "--\r\n",
            head + "Content-Disposition: form-data; name=\"a\u0000\"\r\n\r\nv\r\n--" + B + "--\r\n",
        };
        for (String s : bad) {
            assertEquals(400, status(ascii(s)), s);
        }
    }

    @Test
    void partHeadersOverTheHeaderLimitGet400() {
        JoltConfig cfg = JoltConfig.defaults().maxHeaderBytes(64);
        byte[] payload =
                body(
                        part("form-data; name=\"" + "n".repeat(200) + "\"", null, ascii("v")),
                        close());
        assertEquals(400, status(cfg, "multipart/form-data; boundary=" + B, payload));
    }

    @Test
    void parsingIsCachedPerRequest() {
        App app = Jolt.create();
        app.post("/u", (req, res) -> res.text(String.valueOf(req.parts() == req.parts())));
        JoltTest.of(app)
                .post("/u")
                .header("Content-Type", "multipart/form-data; boundary=" + B)
                .body(body(field("a", "1"), close()))
                .expect(200)
                .body("true");
    }
}
