package dev.opencam.obs;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.view.Surface;
import java.net.*;
import java.util.*;

public final class CameraService extends Service implements StreamServer.Handler, VideoStream.Listener {
    public final class LocalBinder extends Binder { CameraService service() { return CameraService.this; } }
    private final LocalBinder binder = new LocalBinder();
    private StreamServer server;
    private PowerManager.WakeLock wake;
    private WifiManager.MulticastLock multicast;
    private NsdManager.RegistrationListener discovery;
    private volatile VideoStream video;
    volatile Surface preview;
    volatile boolean running, streaming, audioActive, microphone, torch;
    volatile String cameraId = "0", message = "Ready when you are", tally = "idle", format = "Waiting for OBS";
    volatile String lastVideoError = "";
    volatile int fps = 30, bitrate = 12, width = 1920, height = 1080;
    volatile float zoom = 1;
    volatile long frames, bytes;
    private volatile long generation;

    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || "stop".equals(intent.getAction())) { shutdown(); stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        SharedPreferences p = getSharedPreferences("camera", MODE_PRIVATE);
        cameraId = p.getString("camera", "0");
        fps = p.getInt("fps", 30);
        bitrate = p.getInt("bitrate", 12);
        microphone = p.getBoolean("microphone", false) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            message = "Camera permission is required"; stopSelf(); return START_NOT_STICKY;
        }
        try {
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel("camera", "Camera server", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CameraService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "camera")
                .setContentTitle("OpenCam is available to OBS")
                .setContentText("Port 4747 · " + (microphone ? "Camera and microphone" : "Camera only"))
                .setSmallIcon(dev.opencam.obs.R.drawable.ic_camera).setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop camera", stop).build()).build();
            if (Build.VERSION.SDK_INT >= 30) startForeground(1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | (microphone ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0));
            else startForeground(1, notification);
            server = new StreamServer(Protocol.PORT, this);
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCam:stream");
            wake.acquire(); // Released by every shutdown path; streaming must survive screen lock.
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi != null) { multicast = wifi.createMulticastLock("OpenCam:discovery"); multicast.acquire(); }
            generation++;
            running = true;
            lastVideoError = "";
            message = "Listening on port 4747";
            frames = bytes = 0;
            server.start();
            advertise();
        } catch (Exception e) { shutdown(); message = "Cannot start: " + e.getMessage(); stopSelf(); }
        return START_NOT_STICKY;
    }

    private void advertise() {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("OpenCam-" + Build.MODEL.replaceAll("[^A-Za-z0-9-]", "-"));
        info.setServiceType("_droidcamobs._tcp.");
        info.setPort(Protocol.PORT);
        info.setAttribute("name", "OpenCam " + Build.MODEL);
        discovery = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo i) {}
            @Override public void onRegistrationFailed(NsdServiceInfo i, int error) { message = "Discovery unavailable; connect using the IP below"; }
            @Override public void onServiceUnregistered(NsdServiceInfo i) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo i, int error) {}
        };
        try { getSystemService(NsdManager.class).registerService(info, NsdManager.PROTOCOL_DNS_SD, discovery); }
        catch (RuntimeException e) { message = "Discovery unavailable; connect using the IP below"; }
    }

    void setPreview(Surface surface) {
        preview = surface;
        VideoStream v = video;
        if (v != null) v.setPreview(surface);
    }
    void controlsChanged() { VideoStream v = video; if (v != null) v.controlsChanged(); }

    @Override public void video(Protocol.VideoRequest request, StreamServer.Peer peer) throws Exception {
        long g = generation;
        VideoStream v = new VideoStream(this, new VideoStream.Listener() {
            @Override public void format(int w, int h, int rate, String codec) {
                if (running && g == generation) CameraService.this.format(w, h, rate, codec);
            }
            @Override public void frame(int size) {
                if (running && g == generation) CameraService.this.frame(size);
            }
        });
        video = v;
        if (lastVideoError.isEmpty()) message = "Opening camera…";
        try { v.run(request, peer); }
        finally {
            if (g == generation) {
                video = null; streaming = false; tally = "idle"; format = "Waiting for OBS";
                if (running) message = "Waiting for OBS to reconnect";
            }
        }
    }
    @Override public void audio(StreamServer.Peer peer) throws Exception {
        if (!microphone) throw new java.io.IOException("Microphone is off; enable it on the phone before starting");
        long g = generation;
        audioActive = true;
        try { AudioStream.run(peer); } finally { if (g == generation) audioActive = false; }
    }
    @Override public void format(int w, int h, int rate, String codec) {
        lastVideoError = "";
        width = w; height = h; streaming = true;
        format = w + " × " + h + "  /  " + rate + " fps  /  " + (codec.equals("avc") ? "H.264" : "HEVC");
        message = "Connected to OBS";
    }
    @Override public void frame(int size) { frames++; bytes += size; }
    @Override public void tally(String value) { tally = value; }
    @Override public String battery() {
        BatteryManager b = getSystemService(BatteryManager.class);
        return Integer.toString(Math.max(0, b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)));
    }
    @Override public void problem(String error) { message = error; android.util.Log.w("OpenCam", error); }
    @Override public void mediaProblem(boolean video, String error) {
        if (video && !streaming) lastVideoError = error;
        problem(error);
    }

    void shutdown() {
        generation++;
        running = streaming = audioActive = false;
        if (server != null) { server.close(); server = null; }
        if (discovery != null) {
            try { getSystemService(NsdManager.class).unregisterService(discovery); } catch (IllegalArgumentException ignored) {}
            discovery = null;
        }
        if (multicast != null && multicast.isHeld()) multicast.release();
        if (wake != null && wake.isHeld()) wake.release();
        video = null;
        torch = false;
        tally = "idle";
        format = "Waiting for OBS";
        message = "Camera stopped";
        lastVideoError = "";
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onDestroy() { shutdown(); super.onDestroy(); }

    static String addresses() {
        List<String> found = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) found.add(a.getHostAddress());
            }
        } catch (Exception ignored) {}
        return found.isEmpty() ? "No Wi-Fi address · USB is available" : android.text.TextUtils.join("  ·  ", found);
    }
}
