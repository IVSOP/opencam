package dev.opencam.obs;

import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One video and one audio consumer; slow consumers are disconnected, never buffered indefinitely. */
public final class StreamServer implements AutoCloseable {
    public interface Handler {
        void video(Protocol.VideoRequest request, Peer peer) throws Exception;
        void audio(Peer peer) throws Exception;
        String battery();
        void tally(String value);
        void problem(String message);
        default void mediaProblem(boolean video, String message) { problem(message); }
    }
    public static final class Peer implements AutoCloseable {
        private final Socket socket;
        private final OutputStream out;
        volatile long writingSince;
        Peer(Socket s) throws IOException { socket = s; out = s.getOutputStream(); }
        public boolean closed() { return socket.isClosed(); }
        public void send(long pts, byte[] bytes) throws IOException {
            writingSince = System.nanoTime();
            try { Protocol.packet(out, pts, bytes); } finally { writingSince = 0; }
        }
        @Override public void close() { try { socket.close(); } catch (IOException ignored) {} }
    }
    private final Handler handler;
    private final ServerSocket listener;
    private final Set<Peer> peers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean videoBusy = new AtomicBoolean(), audioBusy = new AtomicBoolean();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>(), r -> new Thread(r, "opencam-client"));
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;

    public StreamServer(int port, Handler handler) throws IOException {
        this.handler = handler;
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        try { listener.bind(new InetSocketAddress(port)); }
        catch (IOException e) { listener.close(); throw e; }
    }
    public int port() { return listener.getLocalPort(); }
    public void start() {
        watchdog.scheduleWithFixedDelay(() -> {
            long now = System.nanoTime();
            for (Peer p : peers) if (p.writingSince != 0 && now - p.writingSince > 3_000_000_000L) p.close();
        }, 1, 1, TimeUnit.SECONDS);
        new Thread(() -> {
            while (!closed) {
                try {
                    Socket s = listener.accept();
                    s.setTcpNoDelay(true);
                    s.setSendBufferSize(128 * 1024);
                    s.setSoTimeout(3000);
                    Peer p = new Peer(s);
                    peers.add(p);
                    try { workers.execute(() -> serve(p)); }
                    catch (RejectedExecutionException e) { peers.remove(p); p.close(); }
                } catch (IOException e) { if (!closed) handler.problem(e.toString()); }
            }
        }, "opencam-listener").start();
    }
    private void serve(Peer p) {
        boolean v = false, a = false, media = false, videoRequest = false;
        try {
            InputStream in = p.socket.getInputStream();
            while (!closed) {
                String req = Protocol.readRequest(in);
                if (req == null) break;
                if (req.startsWith("GET /v5/video/")) {
                    media = true;
                    videoRequest = true;
                    Protocol.VideoRequest video = Protocol.video(req);
                    v = videoBusy.compareAndSet(false, true);
                    if (!v) throw new IOException("A video client is already connected");
                    handler.video(video, p);
                    break;
                } else if (req.equals("GET /v2/audio")) {
                    media = true;
                    a = audioBusy.compareAndSet(false, true);
                    if (!a) throw new IOException("An audio client is already connected");
                    handler.audio(p);
                    break;
                } else if (req.startsWith("PUT /v1/tally/")) {
                    String value = req.substring("PUT /v1/tally/".length()).split("/", 2)[0];
                    if (!value.equals("program") && !value.equals("preview") && !value.equals("idle"))
                        throw new IOException("Invalid tally");
                    handler.tally(value);
                    Protocol.http(p.out, 200, "text/plain", "OK");
                } else if (req.startsWith("GET /battery ")) {
                    Protocol.http(p.out, 200, "text/plain", handler.battery());
                } else if (req.equals("GET /ping")) {
                    p.out.write("DroidCam".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    p.out.flush();
                    break;
                } else if (req.startsWith("GET / HTTP/")) {
                    Protocol.http(p.out, 200, "text/html; charset=utf-8",
                        "<!doctype html><meta name=viewport content='width=device-width'><title>OpenCam</title>"
                        + "<body style='background:#101310;color:#b9f56b;font:20px system-ui;padding:10%'>"
                        + "<h1>OpenCam</h1><p>Camera server is ready.</p><p>In OBS, add a DroidCam source, "
                        + "use this IP and port 4747, and select AVC/H.264. Camera controls are on the phone.</p></body>");
                    break;
                } else {
                    Protocol.http(p.out, 404, "text/plain", "Not found");
                    break;
                }
                // The plugin reuses its control socket and expects a receive timeout, not EOF.
                p.socket.setSoTimeout(65000);
            }
        } catch (Exception e) {
            if (!closed && !(e instanceof SocketException) && !(e instanceof EOFException)
                && !(e instanceof SocketTimeoutException)) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                if (media) handler.mediaProblem(videoRequest,message); else handler.problem(message);
            }
            // There is no valid error payload in this receiver's wire protocol.
            // Close instead of emitting 0xffffffff as a fake packet length.
        } finally {
            p.close();
            peers.remove(p);
            if (v) videoBusy.set(false);
            if (a) audioBusy.set(false);
        }
    }
    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) {}
        for (Peer p : peers) p.close();
        watchdog.shutdownNow();
        workers.shutdownNow();
    }
}
