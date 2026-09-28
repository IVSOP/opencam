package dev.opencam.obs;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

/** Wire format derived from the public OBS receiver; media is NOT HTTP. */
public final class Protocol {
    public static final int PORT = 4747;
    public static final long CONFIG_PTS = -1L;
    public static final int MAX_PACKET = 16 * 1024 * 1024;
    private static final Pattern VIDEO = Pattern.compile(
        "GET /v5/video/(avc|hevc|jpg)/(\\d{1,4})x(\\d{1,4})/port/\\d+/os/[^\\r\\n]{0,160}/nonce/\\d+/");

    private Protocol() {}

    public static String readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int n = 0; n < 2048; n++) {
            int b = in.read();
            if (b == -1) {
                if (n == 0) return null;
                throw new EOFException("Incomplete request");
            }
            bytes.write(b);
            String s = bytes.toString("US-ASCII");
            // OBS does not send a newline, HTTP version, or headers for these requests.
            if (s.equals("GET /ping") || s.equals("GET /v2/audio") || VIDEO.matcher(s).matches()) return s;
            if (s.endsWith("\r\n\r\n")) return s;
        }
        throw new IOException("Request exceeds 2048 bytes");
    }

    public static VideoRequest video(String request) throws IOException {
        Matcher m = VIDEO.matcher(request);
        if (!m.matches()) throw new IOException("Invalid video request");
        int w = Integer.parseInt(m.group(2)), h = Integer.parseInt(m.group(3));
        if (w < 320 || h < 240 || w > 3840 || h > 2160 || (w & 1) != 0 || (h & 1) != 0)
            throw new IOException("Unsupported dimensions: " + w + "x" + h);
        if (request.contains("/hdr/1/")) throw new IOException("HDR is not supported; disable HDR in OBS");
        if (m.group(1).equals("jpg")) throw new IOException("Choose AVC/H.264 or HEVC/H.265 in OBS");
        return new VideoRequest(m.group(1), w, h);
    }

    public static void packet(OutputStream out, long pts, byte[] payload) throws IOException {
        if (payload.length == 0 || payload.length > MAX_PACKET || (pts == CONFIG_PTS && payload.length > 1024))
            throw new IOException("Invalid packet size: " + payload.length);
        DataOutputStream d = new DataOutputStream(out);
        d.writeLong(pts);
        d.writeInt(payload.length);
        d.write(payload);
        d.flush();
    }

    public static void http(OutputStream out, int status, String type, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error")
            + "\r\nContent-Type: " + type + "\r\nContent-Length: " + data.length
            + "\r\nConnection: keep-alive\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    public static final class VideoRequest {
        public final String codec;
        public final int width, height;
        VideoRequest(String c, int w, int h) { codec = c; width = w; height = h; }
    }
}
