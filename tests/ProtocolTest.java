package dev.opencam.obs;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;

/** Dependency-free tests against receiver-derived byte fixtures and actual TCP connections. */
public final class ProtocolTest {
    private static final String REQUEST = "GET /v5/video/avc/1920x1080/port/0/os/Linux/obs/32.0/client/2.4/hdr/0/nonce/5912/";
    private static int checks;
    private static void check(boolean value, String what) {
        if (!value) throw new AssertionError(what); checks++;
    }
    private static InputStream input(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII)); }
    private static void rejects(String value) throws Exception {
        try { Protocol.video(value); throw new AssertionError("Accepted invalid request: " + value); }
        catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        check(Arrays.equals(CameraRates.offered(new int[][]{{15,30},{30,30}},new long[]{33333333}),new int[]{30}),
            "30 fps front camera does not advertise 60 fps");
        check(Arrays.equals(CameraRates.offered(new int[][]{{30,60}},new long[]{33333333,16666666}),new int[]{30,60}),
            "60 fps remains available when at least one capture size supports it");
        check(Arrays.equals(CameraRates.offered(new int[][]{{30,60}},new long[]{33333333}),new int[]{30}),
            "AE range alone cannot promise 60 fps at a slow capture size");
        check(Arrays.equals(CameraRates.offered(new int[][]{{15,30}},new long[]{0}),new int[]{30}),
            "unknown duration does not invent a 60 fps AE range");
        check(CameraRates.offered(new int[][]{},new long[]{0}).length==0,"missing capabilities do not invent supported rates");
        check(!CameraRates.durationAllows(33333333,60),"reject 1080p60 when the capture duration is 33 ms");
        check(FrameGeometry.previewHeight(960,1920,1080)==540,"1080p preview is 16:9");
        check(FrameGeometry.previewHeight(960,640,480)==720,"4:3 only when OBS requests 4:3");
        // Four camera quarter-turns, with and without reflection, including real buffer insets.
        int[][] axes = {{1,0,0,1},{0,1,-1,0},{-1,0,0,-1},{0,-1,1,0},
                        {-1,0,0,1},{0,-1,-1,0},{1,0,0,-1},{0,1,1,0}};
        for (int[] axes2 : axes) {
            float a = axes2[0]*.996f, b = axes2[1]*.996f;
            float c = axes2[2]*.996f, d = axes2[3]*.996f;
            float[] matrix = {a,b,0,0, c,d,0,0, 0,0,1,0,
                .002f-Math.min(0,a)-Math.min(0,c), .002f-Math.min(0,b)-Math.min(0,d),0,1};
            float determinant = a*d-b*c;
            FrameGeometry.landscape(matrix);
            check(Math.abs(matrix[1])<.00001 && Math.abs(matrix[4])<.00001,
                "camera buffer axes stay landscape");
            check(Math.abs(matrix[0]*matrix[5]-matrix[1]*matrix[4]-determinant)<.00001,
                "retain camera handedness and full sampled area");
            boolean[] corners = new boolean[4];
            for (int u=0;u<2;u++) for (int v=0;v<2;v++) {
                float x=matrix[0]*u+matrix[4]*v+matrix[12];
                float y=matrix[1]*u+matrix[5]*v+matrix[13];
                check(Math.min(Math.abs(x-.002f),Math.abs(x-.998f))<.00001
                    && Math.min(Math.abs(y-.002f),Math.abs(y-.998f))<.00001,
                    "sample camera boundary rather than a zoomed interior");
                corners[(x>.5f?1:0)+(y>.5f?2:0)]=true;
            }
            check(corners[0]&&corners[1]&&corners[2]&&corners[3],"all four scene corners remain visible");
        }
        check(REQUEST.equals(Protocol.readRequest(input(REQUEST))), "OBS video request has no newline");
        check("GET /v2/audio".equals(Protocol.readRequest(input("GET /v2/audio"))), "bare audio request");
        check("GET /ping".equals(Protocol.readRequest(input("GET /ping"))), "bare ping");
        check(Protocol.video(REQUEST).width == 1920, "resolution parsed");
        rejects(REQUEST.replace("1920x1080", "9999x9999"));
        rejects(REQUEST.replace("1920x1080", "1921x1080"));
        rejects(REQUEST.replace("avc", "jpg"));
        rejects(REQUEST.replace("hdr/0", "hdr/1"));
        rejects("GET /v5/video");
        try { Protocol.readRequest(input("GET /v5/")); throw new AssertionError("Partial request accepted"); }
        catch (EOFException expected) { checks++; }
        try { Protocol.readRequest(input("x".repeat(2049))); throw new AssertionError("Oversized request accepted"); }
        catch (IOException expected) { checks++; }
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        Protocol.packet(packet, 0x0102030405060708L, new byte[]{9, 10, 11});
        check(Arrays.equals(packet.toByteArray(), new byte[]{1,2,3,4,5,6,7,8,0,0,0,3,9,10,11}), "big endian receiver fixture");
        packet.reset(); Protocol.packet(packet, -1, new byte[]{0x11, (byte)0x88});
        ByteBuffer b = ByteBuffer.wrap(packet.toByteArray());
        check(b.getLong() == -1 && b.getInt() == 2 && b.get() == 0x11, "AAC 48kHz mono config sentinel");
        try { Protocol.packet(packet, 0, new byte[0]); throw new AssertionError("Empty packet accepted"); }
        catch (IOException expected) { checks++; }
        try { Protocol.packet(packet, -1, new byte[1025]); throw new AssertionError("Oversized config accepted"); }
        catch (IOException expected) { checks++; }

        CountDownLatch videoEntered = new CountDownLatch(1), releaseVideo = new CountDownLatch(1);
        final String[] tally = {""};
        StreamServer.Handler handler = new StreamServer.Handler() {
            public void video(Protocol.VideoRequest r, StreamServer.Peer p) throws Exception {
                p.send(-1, new byte[]{0,0,0,1,0x67}); p.send(1234, new byte[]{0,0,0,1,0x65});
                videoEntered.countDown(); releaseVideo.await(2, TimeUnit.SECONDS);
            }
            public void audio(StreamServer.Peer p) throws Exception { p.send(-1, new byte[]{0x11,(byte)0x88}); p.send(2000,new byte[]{1}); }
            public String battery() { return "87"; }
            public void tally(String value) { tally[0] = value; }
            public void problem(String value) {}
        };
        try (StreamServer server = new StreamServer(0, handler)) {
            server.start();
            try (Socket video = connect(server)) {
                // Force fragmented writes, including the terminal nonce. No idle-read heuristic is used.
                OutputStream out = video.getOutputStream();
                for (char c : REQUEST.toCharArray()) { out.write(c); out.flush(); }
                DataInputStream in = new DataInputStream(video.getInputStream());
                check(in.readLong() == -1 && in.readInt() == 5, "raw config immediately, no HTTP status line");
                check(in.readNBytes(5)[4] == 0x67, "SPS payload");
                check(in.readLong() == 1234 && in.readInt() == 5, "microsecond timestamp"); in.readNBytes(5);
                check(videoEntered.await(1, TimeUnit.SECONDS), "video handler entered");
                try (Socket second = connect(server)) {
                    second.getOutputStream().write(REQUEST.getBytes(StandardCharsets.US_ASCII));
                    DataInputStream refused = new DataInputStream(second.getInputStream());
                    check(refused.read() == -1, "second video client closes without a fake oversized packet");
                }
                try (Socket audio = connect(server)) {
                    audio.getOutputStream().write("GET /v2/audio".getBytes(StandardCharsets.US_ASCII));
                    DataInputStream ain = new DataInputStream(audio.getInputStream());
                    check(ain.readLong() == -1 && ain.readInt() == 2, "audio works alongside video");
                }
                try (Socket control = connect(server)) {
                    control.getOutputStream().write("PUT /v1/tally/program/ HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    check(readHttp(control).equals("OK"), "tally response");
                    check(tally[0].equals("program"), "tally state");
                    control.getOutputStream().write("GET /battery HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    check(readHttp(control).equals("87"), "same control socket reused");
                }
                releaseVideo.countDown();
            }
        }
        java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>();
        StreamServer.Handler unsupported = new StreamServer.Handler() {
            public void video(Protocol.VideoRequest r, StreamServer.Peer p) throws Exception { throw new IOException("Camera supports 30 fps only"); }
            public void audio(StreamServer.Peer p) {}
            public String battery() { return "100"; }
            public void tally(String value) {}
            public void problem(String value) { failure.set(value); }
        };
        try (StreamServer server = new StreamServer(0,unsupported)) {
            server.start();
            for (int attempt=0;attempt<2;attempt++) try (Socket client = connect(server)) {
                client.getOutputStream().write(REQUEST.getBytes(StandardCharsets.US_ASCII));
                check(client.getInputStream().read()==-1,"failed camera request closes cleanly on each retry");
                check("Camera supports 30 fps only".equals(failure.get()),"underlying camera error is reported");
            }
        }
        CountDownLatch slowExited = new CountDownLatch(1);
        StreamServer.Handler slowHandler = new StreamServer.Handler() {
            public void video(Protocol.VideoRequest r, StreamServer.Peer p) throws Exception {
                try { for (int i = 0; i < 100; i++) p.send(i, new byte[1024 * 1024]); }
                finally { slowExited.countDown(); }
            }
            public void audio(StreamServer.Peer p) {}
            public String battery() { return "100"; }
            public void tally(String value) {}
            public void problem(String value) {}
        };
        try (StreamServer slow = new StreamServer(0, slowHandler)) {
            slow.start();
            try (Socket client = connect(slow)) {
                client.setReceiveBufferSize(1024);
                client.getOutputStream().write(REQUEST.getBytes(StandardCharsets.US_ASCII));
                check(slowExited.await(8, TimeUnit.SECONDS), "watchdog releases a blocked network writer");
            }
        }
        StreamServer stopped = new StreamServer(0, handler);
        stopped.start();
        try (Socket client = connect(stopped)) {
            client.getOutputStream().write("PUT /v1/tally/idle/ HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            readHttp(client);
            stopped.close();
            check(client.getInputStream().read() == -1, "stop closes existing control connections");
        } finally { stopped.close(); }
        System.out.println("PASS: " + checks + " protocol and TCP checks");
    }
    private static Socket connect(StreamServer s) throws IOException {
        Socket socket = new Socket("127.0.0.1", s.port()); socket.setSoTimeout(3000); return socket;
    }
    private static String readHttp(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        String header = Protocol.readRequest(in);
        int start = header.indexOf("Content-Length: ") + 16;
        int length = Integer.parseInt(header.substring(start, header.indexOf("\r\n", start)));
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
}
