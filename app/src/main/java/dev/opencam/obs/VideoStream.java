package dev.opencam.obs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.os.*;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** Camera2 -> GPU transform -> encoder and matching preview; no CPU pixel copies. */
final class VideoStream {
    interface Listener { void format(int width, int height, int fps, String codec); void frame(int bytes); }
    private final CameraService owner;
    private final Listener listener;
    private final HandlerThread cameraThread = new HandlerThread("opencam-camera");
    private android.os.Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CameraCharacteristics characteristics;
    private MediaCodec encoder;
    private Surface encoderSurface;
    private FrameRenderer renderer;
    private Surface cameraSurface;
    private volatile boolean stopped;
    private volatile String failure;
    private int fps;
    private Range<Integer> fpsRange;
    private CountDownLatch configured;
    private int sessionGeneration;

    VideoStream(CameraService owner, Listener listener) { this.owner = owner; this.listener = listener; }

    @SuppressLint("MissingPermission") // Service can only be started after the activity grants camera permission.
    void run(Protocol.VideoRequest request, StreamServer.Peer peer) throws Exception {
        cameraThread.start();
        cameraHandler = new android.os.Handler(cameraThread.getLooper());
        try {
            CameraManager manager = (CameraManager) owner.getSystemService(Context.CAMERA_SERVICE);
            String id = owner.cameraId;
            characteristics = manager.getCameraCharacteristics(id);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IOException("This camera has no video output");
            Size desired = new Size(request.width, request.height);
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || !Arrays.asList(sizes).contains(desired))
                throw new IOException("Camera cannot capture " + desired + ".\n" + CameraCapabilities.alternatives(characteristics,map));
            fps = owner.fps;
            try { fpsRange = chooseFps(characteristics, fps); }
            catch (IOException e) { throw new IOException(e.getMessage() + "\n" + CameraCapabilities.alternatives(characteristics,map)); }
            fps = Math.min(fps, fpsRange.getUpper());
            long duration = map.getOutputMinFrameDuration(SurfaceTexture.class, desired);
            if (!CameraRates.durationAllows(duration,fps))
                throw new IOException("Camera cannot deliver " + desired + " at " + fps + " fps.\n" + CameraCapabilities.alternatives(characteristics,map));
            String mime = request.codec.equals("hevc") ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
            MediaFormat f = MediaFormat.createVideoFormat(mime, request.width, request.height);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, owner.bitrate * 1_000_000);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            if (Build.VERSION.SDK_INT >= 29) f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            if (request.codec.equals("avc")) f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            String codecName = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f);
            if (codecName == null) throw new IOException("No encoder supports this format/bitrate; try H.264, 1080p, 12 Mbps");
            encoder = MediaCodec.createByCodecName(codecName);
            encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();
            renderer = new FrameRenderer(encoderSurface, request.width, request.height, error -> failure = error);
            cameraSurface = renderer.input();
            renderer.setPreview(owner.preview);
            configured = new CountDownLatch(1);
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (stopped) { device.close(); return; }
                    camera = device;
                    configureSession();
                }
                @Override public void onDisconnected(CameraDevice device) { failure = "Camera disconnected"; device.close(); configured.countDown(); }
                @Override public void onError(CameraDevice device, int error) { failure = "Camera error " + error; device.close(); configured.countDown(); }
            }, cameraHandler);
            if (!configured.await(8, TimeUnit.SECONDS)) throw new IOException("Timed out opening camera");
            if (failure != null) throw new IOException(failure);
            listener.format(request.width, request.height, fps, request.codec);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sentConfig = false;
            long lastFrame = System.nanoTime();
            while (!stopped && !peer.closed() && !Thread.currentThread().isInterrupted()) {
                if (failure != null) throw new IOException(failure);
                int index = encoder.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = encoder.getOutputFormat();
                    ByteArrayOutputStream config = new ByteArrayOutputStream();
                    for (int i = 0; i < 3; i++) {
                        ByteBuffer csd = output.getByteBuffer("csd-" + i);
                        if (csd != null) config.write(copy(csd));
                    }
                    if (config.size() > 0 && !sentConfig) {
                        peer.send(Protocol.CONFIG_PTS, config.toByteArray());
                        sentConfig = true;
                    }
                } else if (index >= 0) {
                    try {
                        if (info.size > 0) {
                            ByteBuffer buffer = encoder.getOutputBuffer(index);
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            byte[] bytes = copy(buffer);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                if (!sentConfig) { peer.send(Protocol.CONFIG_PTS, bytes); sentConfig = true; }
                            } else {
                                if (!sentConfig) throw new IOException("Encoder produced video without codec configuration");
                                peer.send(info.presentationTimeUs, bytes);
                                listener.frame(bytes.length);
                                lastFrame = System.nanoTime();
                            }
                        }
                    } finally { encoder.releaseOutputBuffer(index, false); }
                }
                if (System.nanoTime() - lastFrame > 8_000_000_000L) throw new IOException("Camera stopped delivering frames");
            }
        } finally { close(); }
    }

    private static Range<Integer> chooseFps(CameraCharacteristics c, int fps) throws IOException {
        Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) {
            Range<Integer> best = null;
            for (Range<Integer> r : ranges) if (r.contains(fps)
                && (best == null || Math.abs(r.getUpper() - fps) * 100 + fps - r.getLower()
                < Math.abs(best.getUpper() - fps) * 100 + fps - best.getLower())) best = r;
            if (best != null) return best;
        }
        throw new IOException("Camera does not support " + fps + " fps; select 30 fps");
    }

    private void configureSession() {
        if (stopped || camera == null) return;
        int generation = ++sessionGeneration;
        if (session != null) { session.close(); session = null; }
        List<Surface> surfaces = new ArrayList<>();
        surfaces.add(cameraSurface);
        try {
            camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (stopped || generation != sessionGeneration) { s.close(); return; }
                    session = s;
                    applyControls(surfaces);
                    configured.countDown();
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    if (generation != sessionGeneration || stopped) return;
                    failure = "Camera cannot configure this video size"; configured.countDown();
                }
            }, cameraHandler);
        } catch (Exception e) { failure = e.toString(); configured.countDown(); }
    }

    private void applyControls(List<Surface> surfaces) {
        try {
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            for (Surface s : surfaces) b.addTarget(s);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (modes != null) for (int mode : modes) if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                b.set(CaptureRequest.CONTROL_AF_MODE, mode);
            if (Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)))
                b.set(CaptureRequest.FLASH_MODE, owner.torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            Rect sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float max = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (sensor != null && max != null) {
                float zoom = Math.min(max, Math.max(1, owner.zoom));
                int w = (int)(sensor.width() / zoom), h = (int)(sensor.height() / zoom);
                b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(sensor.centerX() - w / 2, sensor.centerY() - h / 2,
                    sensor.centerX() + w / 2, sensor.centerY() + h / 2));
            }
            session.setRepeatingRequest(b.build(), null, cameraHandler);
        } catch (Exception e) { failure = e.toString(); }
    }

    void setPreview(Surface surface) {
        FrameRenderer r = renderer;
        if (r != null) r.setPreview(surface);
    }
    void controlsChanged() {
        if (cameraHandler != null) cameraHandler.post(() -> {
            if (stopped || session == null) return;
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(cameraSurface);
            applyControls(surfaces);
        });
    }
    static byte[] copy(ByteBuffer source) {
        ByteBuffer b = source.duplicate(); byte[] bytes = new byte[b.remaining()]; b.get(bytes); return bytes;
    }
    private void close() {
        stopped = true;
        if (renderer != null) renderer.stopDrawing();
        boolean interrupted = Thread.interrupted();
        CountDownLatch done = new CountDownLatch(1);
        if (cameraHandler != null) {
            cameraHandler.post(() -> {
                if (session != null) session.close();
                if (camera != null) camera.close();
                done.countDown();
            });
            try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { interrupted = true; }
        }
        if (encoder != null) { try { encoder.stop(); } catch (RuntimeException ignored) {} encoder.release(); }
        if (renderer != null) renderer.close();
        if (encoderSurface != null) encoderSurface.release();
        cameraThread.quitSafely();
        if (interrupted) Thread.currentThread().interrupt();
    }
}
