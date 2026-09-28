# OpenCam

A small, independent Android camera app for the **existing DroidCam OBS plugin**. Native Android UI, Camera2 capture, GPU-corrected MediaCodec surface encoding, and no accounts, ads, watermark, analytics, or runtime libraries. Not affiliated with DroidCam or DEV47APPS.

The implementation was derived from the public OBS receiver in `../droidcam-obs-plugin`; no Android APK was decompiled. The OBS plugin does not need changes.

## Install and connect

Requires **Android 8.0 or later**. Install the provided [`dist/opencam-debug.apk`](dist/opencam-debug.apk), or build it below. This is a debug-signed development build, not a store release.

```sh
adb install -r dist/opencam-debug.apk
```

1. Open **OpenCam**. Choose the camera, frame rate and bitrate. Enable the microphone if wanted.
2. Tap **Start camera**, then allow the requested permissions. The server listens on port **4747** until you stop it.
3. In OBS, add a **DroidCam** source. Refresh the device list, or enter the phone's displayed Wi-Fi IP and port **4747**.
4. Start with **AVC/H.264**, **1920×1080**, **HDR off**, and Activate. Try **1280×720** if the camera rejects 1080p. Select audio in OBS only if you enabled the microphone on the phone.
5. The phone shows a live preview and stream statistics when OBS connects. Stop using the app button or its notification.

Phone and computer must be on the same reachable network. Guest Wi-Fi/client isolation and VPNs can block connections. Discovery advertises `_droidcamobs._tcp`; manual IP entry works when multicast discovery is unavailable. Close the original DroidCam app if it is already listening on port 4747.

**USB:** enable USB debugging and authorize the computer, then select the Android USB device in the plugin. The plugin handles ADB port forwarding. Manual fallback:

```sh
adb forward tcp:4747 tcp:4747
# In the OBS source: Wi-Fi/manual address 127.0.0.1, port 4747.
```

Use `adb -s SERIAL` when multiple Android devices are connected. No root or special USB driver protocol is required by this app.

## Controls and limits

| Control | Behavior |
| --- | --- |
| Camera | Cameras exposed by Android Camera2; select before starting |
| Frame rate | 30/60 fps choices filtered by the selected camera's reported capture modes; the requested OBS resolution and encoder are checked on connection |
| Bitrate | 6, 12, 20 or 40 Mbps encoder target; actual output depends on content and encoder |
| Resolution | Requested by OBS, up to 3840×2160 if camera and encoder support the exact size |
| Codec | H.264; optional HEVC on supported encoders |
| Audio | Opt-in AAC-LC, 48 kHz mono, 128 kbps |
| Focus | Continuous video autofocus when the camera supports it |
| Zoom / light | Live digital zoom (clamped to camera capability) and torch |
| Tally | OBS program / preview / connected indicators |

- **H.264 SDR is the tested path.** MJPEG and HDR are not implemented. Unsupported settings produce a message on the phone instead of silently sending a different codec or resolution.
- **Version 0.1.3:** a saved frame rate unsupported by the selected camera prompts you to choose a supported rate before starting. Capture errors remain visible across OBS retries, with camera-reported alternative resolutions where available. Rejected connections close cleanly instead of sending the invalid `4294967295` packet length used by earlier builds.
- Capture starts when OBS connects. The idle screen is not a camera preview. The preview can be detached while the foreground service continues streaming.
- One video and one audio connection at a time. Slow readers are disconnected after a blocked write lasts roughly 3–4 seconds, preventing unlimited queues. OBS can reconnect.
- **Version 0.1.2:** output stays in the camera buffer's landscape orientation. Hold the phone horizontally for upright landscape video; holding it vertically deliberately produces a sideways picture. This removes 0.1.1's extra portrait-to-landscape crop/zoom. Preview and OBS show the same full camera buffer, with the producer's texture-origin correction retained. The preview follows the requested OBS aspect ratio. The camera's normal sensor-aspect crop and any user-selected zoom still apply. Stop hides the previous frame.
- Stopping releases sockets, capture sessions, encoder, microphone, discovery registration and wake lock. The service is not automatically restarted after process death or reboot.
- The existing plugin protocol has no authentication or encryption. While started, the server is reachable on the phone's network interfaces. Use a trusted network; avoid port forwarding from the internet. The app does not contact cloud services.
- Physical-phone image quality, sustained thermals, battery use, mDNS on real networks, 60 fps, 4K and HEVC still require device testing. There is no claim that a software change can overcome a phone's sensor or encoder limits.

## Xiaomi 13T front-camera setup

Use **30 fps**, **1920×1080**, **H.264**, **HDR off**. Start with 12 Mbps; try 1280×720 if Android rejects 1080p. Xiaomi lists front-camera video at [1080p30 and 720p30](https://www.mi.com/my/support/faq/details/KA-43103/). A higher bitrate does not enable 60 fps or a higher capture resolution. OpenCam reads the phone's Camera2 capabilities rather than hardcoding model limits.

For portrait output, hold the phone upright and rotate the source 90° in OBS. That display transform does not change the capture resolution or frame rate requested from the phone. If connection fails, read the persistent error on the phone and select one of its listed modes in OBS.

## Build

Open this folder in Android Studio, or use JDK **17–25** (tested with **21**), Android SDK platform **36**, build tools **36.0.0**, Gradle **9.1.0** and Android Gradle Plugin **9.0.1**. The Gradle wrapper is included. The app targets API 35 and has no runtime dependency downloads; the first build downloads build tooling.

```sh
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug lintDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Alternatively set `sdk.dir=/path/to/android-sdk` in an untracked `local.properties`. To publish, configure your own release signing key; do not distribute a debug key as a production key.

## Verify compatibility

```sh
sh tools/test-protocol.sh
# Requires a JDK and permission to open loopback sockets; no Android device needed.

python3 tools/probe.py PHONE_IP --size 1280x720 --audio
# Requires Python 3, ffmpeg, a running app, and microphone enabled for --audio.
# Saves compressed media and the exact framed packets under captures/.

sh tools/test-receiver.sh ../droidcam-obs-plugin avc captures/video.wire
sh tools/test-receiver.sh ../droidcam-obs-plugin audio captures/audio.wire
# Requires C++, pkg-config, OBS and FFmpeg development packages.
# Compiles the original plugin's unmodified ffmpeg_decode.cc against the captured packets.
```

See [protocol notes](docs/protocol.md) for the wire format and [validation results](docs/validation.md) for what was actually tested. Android implementation references: [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec), [Camera2](https://developer.android.com/reference/android/hardware/camera2/package-summary), and [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

## Source map

- `Protocol.java`: bare request parsing, big-endian frame envelopes, control responses.
- `StreamServer.java`: bounded client pool, one consumer per media stream, write watchdog.
- `VideoStream.java`: camera capabilities, capture session, AVC/HEVC surface encoder.
- `CameraCapabilities.java` / `CameraRates.java`: per-camera frame-rate choices and reported OBS-compatible capture modes.
- `FrameRenderer.java` / `FrameGeometry.java`: shared GPU landscape transform, full-frame encoder and preview output.
- `AudioStream.java`: PCM microphone capture and AAC encoding.
- `CameraService.java`: permissions, foreground notification, discovery, lifecycle and status.
- `MainActivity.java`: native controls, camera preview and connection guide.

Licensed under GPL-2.0-or-later; see [LICENSE](LICENSE). Protocol provenance is documented separately; no proprietary DroidCam assets are included.
