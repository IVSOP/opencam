package dev.opencam.obs;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Native controls, no network UI framework, analytics, account, or runtime dependencies. */
public final class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int BG = 0xff101310, CARD = 0xff1c211b, MUTED = 0xffa5b19e, INK = 0xfff1f5eb, GREEN = 0xffb9f56b;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private CameraService service;
    private boolean bound, visible;
    private SharedPreferences prefs;
    private TextView status, detail, rate, tally, address, previewHint;
    private Button start, lens, fps, quality, torch;
    private Switch microphone;
    private TextureView texture;
    private FrameLayout previewFrame;
    private boolean previewFrameReady;
    private Surface preview;
    private SeekBar zoom;
    private final List<String> cameraIds = new ArrayList<>(), cameraNames = new ArrayList<>();
    private long lastFrames, lastBytes, lastTick;
    private String addressCache = "";
    private int ticks;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((CameraService.LocalBinder) binder).service();
            if (preview != null && visible) service.setPreview(preview);
            refresh();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); if (visible) ui.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("camera", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        loadCameras();
        buildUi();
        bound = bindService(new Intent(this, CameraService.class), connection, BIND_AUTO_CREATE);
    }
    private void loadCameras() {
        try {
            CameraManager manager = getSystemService(CameraManager.class);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                String label = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? "Front" : "Back";
                float[] lengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (lengths != null && lengths.length > 0) label += String.format(Locale.US, " · %.1f mm", lengths[0]);
                cameraIds.add(id); cameraNames.add(label);
            }
            if (!cameraIds.isEmpty() && !cameraIds.contains(prefs.getString("camera", "")))
                prefs.edit().putString("camera", cameraIds.get(0)).apply();
        } catch (CameraAccessException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setIncludeFontPadding(false); return t;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private void gap(LinearLayout l, int size) { View v = new View(this); l.addView(v, new LinearLayout.LayoutParams(1, dp(size))); }
    private Button button(String label, boolean primary) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(15);
        b.setTextColor(primary ? BG : INK); b.setBackground(background(primary ? GREEN : CARD, 14));
        b.setMinHeight(dp(48)); b.setPadding(dp(12), dp(6), dp(12), dp(6)); return b;
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout root = column(); root.setPadding(dp(24), dp(20), dp(24), dp(24));
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            root.setPadding(dp(24) + insets.getSystemWindowInsetLeft(), dp(20) + insets.getSystemWindowInsetTop(),
                dp(24) + insets.getSystemWindowInsetRight(), dp(24) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        TextView eyebrow = text("YOUR PHONE. YOUR CAMERA.", 11, GREEN); eyebrow.setLetterSpacing(.16f); root.addView(eyebrow);
        gap(root, 7);
        LinearLayout title = new LinearLayout(this); title.setGravity(Gravity.CENTER_VERTICAL);
        TextView wordmark = text("OpenCam", 34, INK); wordmark.setTypeface(null, Typeface.BOLD);
        title.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        tally = text("OFFLINE", 11, MUTED); tally.setPadding(dp(12), dp(8), dp(12), dp(8)); tally.setBackground(background(CARD, 24)); title.addView(tally);
        root.addView(title); gap(root, 20);

        FrameLayout frame = new FrameLayout(this); previewFrame = frame; frame.setBackground(background(0xff080b08, 20)); frame.setClipToOutline(true);
        texture = new TextureView(this); texture.setSurfaceTextureListener(this); frame.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        previewHint = text("A clear view starts here.\n\nStart the camera, then connect OBS.\nYour live preview will appear here.", 15, MUTED);
        previewHint.setBackgroundColor(0xff080b08);
        previewHint.setGravity(Gravity.CENTER); previewHint.setPadding(dp(20), dp(20), dp(20), dp(20)); frame.addView(previewHint, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams rateLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        rateLayout.setMargins(dp(14), 0, dp(14), dp(14));
        rate = text("DIRECT TO OBS", 10, GREEN); rate.setPadding(dp(9), dp(6), dp(9), dp(6)); rate.setBackground(background(0xcc101310, 8)); frame.addView(rate, rateLayout);
        root.addView(frame, new LinearLayout.LayoutParams(-1, dp(200)));
        frame.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> transform());
        gap(root, 18);
        status = text("Ready when you are", 19, INK); status.setTypeface(null, Typeface.BOLD); root.addView(status);
        gap(root, 6); detail = text("Camera stays private until you start.", 13, MUTED); root.addView(detail); gap(root, 20);

        LinearLayout endpoint = column(); endpoint.setPadding(dp(16), dp(14), dp(16), dp(14)); endpoint.setBackground(background(CARD, 16));
        endpoint.addView(text("CONNECT FROM OBS", 10, GREEN)); gap(endpoint, 8);
        address = text("Finding local address…", 17, INK); address.setTextIsSelectable(true); endpoint.addView(address); gap(endpoint, 6);
        endpoint.addView(text("Port 4747  ·  Wi-Fi or USB", 12, MUTED));
        root.addView(endpoint); gap(root, 18);

        LinearLayout choices = new LinearLayout(this);
        lens = button("Camera", false); fps = button("30 fps", false); quality = button("12 Mbps", false);
        for (Button b : new Button[]{lens, fps, quality}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1); lp.setMargins(0, 0, dp(6), 0); choices.addView(b, lp);
        }
        root.addView(choices); gap(root, 10);
        lens.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Choose camera")
            .setItems(cameraNames.toArray(new String[0]), (d, which) -> { prefs.edit().putString("camera", cameraIds.get(which)).apply(); refresh(); }).show());
        fps.setOnClickListener(v -> showFrameRates());
        quality.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Video bitrate")
            .setItems(new String[]{"6 Mbps · lighter Wi-Fi", "12 Mbps · balanced", "20 Mbps · fine detail", "40 Mbps · 4K / USB"}, (d, which) -> {
                prefs.edit().putInt("bitrate", new int[]{6, 12, 20, 40}[which]).apply(); refresh(); }).show());

        LinearLayout audioRow = new LinearLayout(this); audioRow.setGravity(Gravity.CENTER_VERTICAL);
        microphone = new Switch(this); microphone.setText("Microphone"); microphone.setTextColor(INK); microphone.setTextSize(15);
        microphone.setChecked(prefs.getBoolean("microphone", false));
        microphone.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("microphone", checked).apply();
            if (checked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 20);
        });
        audioRow.addView(microphone, new LinearLayout.LayoutParams(0, dp(50), 1));
        torch = button("Light off", false); audioRow.addView(torch, new LinearLayout.LayoutParams(dp(100), dp(44)));
        torch.setOnClickListener(v -> { if (service != null) { service.torch = !service.torch; service.controlsChanged(); refresh(); } });
        root.addView(audioRow);
        LinearLayout zoomRow = new LinearLayout(this); zoomRow.setGravity(Gravity.CENTER_VERTICAL);
        zoomRow.addView(text("Zoom", 13, MUTED)); zoom = new SeekBar(this); zoom.setMax(30);
        zoomRow.addView(zoom, new LinearLayout.LayoutParams(0, dp(48), 1)); root.addView(zoomRow);
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int value, boolean fromUser) { if (fromUser && service != null) { service.zoom = 1 + value / 10f; service.controlsChanged(); } }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        gap(root, 8); start = button("Start camera", true); start.setTextSize(17); root.addView(start, new LinearLayout.LayoutParams(-1, dp(58)));
        start.setOnClickListener(v -> {
            if (service != null && service.running) startService(new Intent(this, CameraService.class).setAction("stop"));
            else begin();
        });
        gap(root, 14);
        TextView help = text("Connection guide  ↗", 13, MUTED); help.setGravity(Gravity.CENTER); help.setPadding(0, dp(10), 0, dp(10)); root.addView(help);
        help.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Connect to OBS")
            .setMessage("1. Start OpenCam here.\n\n2. In OBS, add a DroidCam source. Refresh the device list or enter this phone’s IP and port 4747.\n\n3. Choose AVC/H.264, 1920×1080 (or 1280×720), HDR off, then Activate. Resolution is selected in OBS.\n\nUSB: enable Android USB debugging and authorize your computer. Select the USB device in OBS.\n\nHold the phone horizontally for landscape video. Upright, the picture is intentionally sideways. The preview matches OBS and preserves the full camera frame.\n\nAnyone on the local network can connect while the server is started. Use a trusted network. Stop releases the camera and microphone.")
            .setPositiveButton("Got it", null).show());
        setContentView(scroll);
    }

    private int[] availableRates() throws CameraAccessException {
        return CameraCapabilities.frameRates(this,prefs.getString("camera","0"));
    }
    private void showFrameRates() {
        try {
            int[] rates = availableRates();
            if (rates.length == 0) {
                showCameraError("This camera does not report a supported 30 or 60 fps OBS mode.");
                return;
            }
            String[] labels = new String[rates.length];
            for (int i=0;i<rates.length;i++) labels[i] = rates[i]+" fps";
            new AlertDialog.Builder(this).setTitle("Frame rate · selected camera")
                .setItems(labels,(d,which) -> { prefs.edit().putInt("fps",rates[which]).apply(); refresh(); }).show();
        } catch (Exception e) { showCameraError(e.getMessage()); }
    }
    private void showCameraError(String message) {
        new AlertDialog.Builder(this).setTitle("Camera settings")
            .setMessage(message).setPositiveButton("OK",null).show();
    }
    private void begin() {
        if (cameraIds.isEmpty()) { status.setText("No camera available"); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 10); return;
        }
        try {
            int[] rates = availableRates();
            int requested = prefs.getInt("fps",30);
            boolean supported = false;
            for (int rate : rates) if (rate == requested) supported = true;
            if (!supported) {
                if (rates.length == 0) { showCameraError("This camera does not report a supported OBS video mode."); return; }
                int replacement = rates[0];
                new AlertDialog.Builder(this).setTitle(requested+" fps unavailable")
                    .setMessage("The selected camera does not report "+requested+" fps. Use "+replacement+" fps to start streaming.")
                    .setPositiveButton("Use "+replacement+" fps",(d,w) -> {
                        prefs.edit().putInt("fps",replacement).apply(); refresh(); begin();
                    }).setNegativeButton("Cancel",null).show();
                return;
            }
        } catch (Exception e) { showCameraError("Cannot read camera capabilities: "+e.getMessage()); return; }
        if (prefs.getBoolean("microphone", false) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 21); return;
        }
        startForegroundService(new Intent(this, CameraService.class).setAction("start"));
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 30);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        boolean allowed = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (request == 10) {
            if (allowed) begin(); else status.setText("Allow camera access in Android settings to stream");
        } else if (request == 20 || request == 21) {
            if (!allowed) { microphone.setChecked(false); prefs.edit().putBoolean("microphone", false).apply(); }
            if (request == 21) begin();
        }
    }

    private static void label(TextView view, String value) {
        if (!view.getText().toString().equals(value)) view.setText(value);
    }
    private void refresh() {
        if (status == null) return;
        boolean running = service != null && service.running, streaming = running && service.streaming;
        label(start, running ? "Stop camera" : "Start camera");
        lens.setEnabled(!running); fps.setEnabled(!running); quality.setEnabled(!running); microphone.setEnabled(!running);
        int index = cameraIds.indexOf(prefs.getString("camera", "0"));
        label(lens, index >= 0 ? cameraNames.get(index).split(" · ")[0] + " camera" : "Camera");
        label(fps, prefs.getInt("fps", 30) + " fps"); label(quality, prefs.getInt("bitrate", 12) + " Mbps");
        torch.setEnabled(streaming && flashAvailable()); zoom.setEnabled(streaming);
        label(torch, service != null && service.torch ? "Light on" : "Light off");
        String videoError = running && !streaming ? service.lastVideoError : "";
        if (service != null) label(status, videoError.isEmpty() ? service.message : "Camera could not start");
        label(previewHint, videoError.isEmpty()
            ? "A clear view starts here.\n\nStart the camera, then connect OBS.\nYour live preview will appear here."
            : videoError);
        label(detail, streaming ? service.format : running ? videoError.isEmpty() ? "Select this phone in your DroidCam OBS source." : "Change resolution in OBS, or stop to change camera settings." : "Camera stays private until you start.");
        if (!streaming) previewFrameReady = false;
        texture.setAlpha(streaming ? 1f : 0f);
        previewHint.setVisibility(streaming && previewFrameReady ? View.GONE : View.VISIBLE);
        String state = !running ? "OFFLINE" : !streaming ? "READY" : service.tally.equals("program") ? "● ON AIR" : service.tally.equals("preview") ? "PREVIEW" : "CONNECTED";
        label(tally, state); tally.setTextColor(state.contains("ON AIR") ? 0xffff8b79 : running ? GREEN : MUTED);
        if (ticks++ % 10 == 0) addressCache = CameraService.addresses();
        label(address, addressCache);
        long now = SystemClock.elapsedRealtime();
        if (streaming && lastTick > 0 && now > lastTick) {
            double seconds = (now - lastTick) / 1000.0;
            label(rate, String.format(Locale.US, "%.0f FPS  ·  %.1f Mbps%s", Math.max(0, service.frames - lastFrames) / seconds,
                Math.max(0, service.bytes - lastBytes) * 8 / seconds / 1_000_000, service.audioActive ? "  ·  MIC" : ""));
        } else label(rate, "DIRECT TO OBS");
        lastTick = now; lastFrames = service == null ? 0 : service.frames; lastBytes = service == null ? 0 : service.bytes;
        transform();
    }
    private boolean flashAvailable() {
        try { return Boolean.TRUE.equals(getSystemService(CameraManager.class).getCameraCharacteristics(service.cameraId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)); }
        catch (Exception e) { return false; }
    }
    private void transform() {
        if (previewFrame == null || previewFrame.getWidth() == 0) return;
        int w = service == null ? 1920 : service.width;
        int h = service == null ? 1080 : service.height;
        int height = FrameGeometry.previewHeight(previewFrame.getWidth(), w, h);
        if (previewFrame.getLayoutParams().height != height) {
            previewFrame.getLayoutParams().height = height;
            previewFrame.requestLayout();
        }
        // GL has already applied the landscape transform to the full OBS camera frame.
        // Applying another camera transform here would flip/rotate it a second time.
        texture.setTransform(new Matrix());
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        surface.setDefaultBufferSize(1280, 720); previewFrameReady = false; preview = new Surface(surface);
        if (service != null && visible) service.setPreview(preview); transform();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { transform(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (service != null) service.setPreview(null);
        if (preview != null) { preview.release(); preview = null; } return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        if (service != null && service.streaming) { previewFrameReady = true; previewHint.setVisibility(View.GONE); }
    }
    @Override public void onResume() { super.onResume(); visible = true; if (service != null && preview != null) service.setPreview(preview); ui.post(tick); }
    @Override public void onPause() { visible = false; ui.removeCallbacks(tick); if (service != null) service.setPreview(null); super.onPause(); }
    @Override public void onDestroy() { if (bound) unbindService(connection); super.onDestroy(); }
}
