import java.io.*; import java.net.*; import java.util.*;
public class Idle {
  static long probe(int port) {
    long t0 = System.nanoTime();
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress("127.0.0.1", port), 5000); s.setSoTimeout(15000);
      s.getOutputStream().write("GET /ping HTTP/1.1\r\nHost: x\r\nConnection: keep-alive\r\n\r\n".getBytes());
      InputStream in = s.getInputStream(); ByteArrayOutputStream b = new ByteArrayOutputStream(); int c;
      while ((c = in.read()) != -1) { b.write(c); String t = b.toString();
        int h = t.indexOf("\r\n\r\n"); if (h > 0) { int i = t.toLowerCase().indexOf("content-length:"); int len = i < 0 ? 0 : Integer.parseInt(t.substring(i + 15, t.indexOf("\r\n", i)).trim());
          if (t.length() - (h + 4) >= len) break; } }
      return (System.nanoTime() - t0) / 1_000_000;
    } catch (Exception e) { return -1; }
  }
  public static void main(String[] a) throws Exception {
    int port = Integer.parseInt(a[0]), n = Integer.parseInt(a[1]);
    List<Socket> open = new ArrayList<>(); int fail = 0;
    for (int i = 0; i < n; i++) {
      try { Socket s = new Socket(); s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        s.getOutputStream().write("GET /ping HTTP/1.1\r\nHost: x\r\nConnection: keep-alive\r\n\r\n".getBytes()); open.add(s); }
      catch (Exception e) { fail++; }
      if (i % 50 == 49) Thread.sleep(50);
    }
    System.out.println("OPENED " + open.size() + " failed " + fail); System.out.flush();
    Thread.sleep(6000);
    long[] l = new long[15]; int bad = 0; for (int i = 0; i < l.length; i++) { l[i] = probe(port); if (l[i] < 0) bad++; Thread.sleep(100); }
    long[] g = Arrays.stream(l).filter(x -> x >= 0).sorted().toArray();
    System.out.println("PROBE ok " + g.length + " failed " + bad + (g.length > 0 ? " median_ms " + g[g.length / 2] + " max_ms " + g[g.length - 1] : ""));
    System.out.flush(); Thread.sleep(6000);
    long alive = open.stream().filter(s -> { try { return !s.isClosed() && s.getInputStream().available() >= 0; } catch (Exception e) { return false; } }).count();
    System.out.println("HELD " + alive + " DONE");
  }
}
