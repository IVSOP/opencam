# Validation

Tested with the sibling OBS plugin at commit `ddfa8880451f088e19b0358635922cf000bcda6d`. The sections below distinguish each build's validation.

## Version 0.1.3 — capture capabilities and useful errors (2026-09-28)

- Build and lint pass with **0 errors, 6 warnings**; the current lint output is retained in `lint-results.txt`.
- **94 host assertions pass**, including filtering 60 fps by AE range and frame duration, preserving it for capable cameras, clean EOF on capture rejection and repeated failed requests, plus the existing protocol, socket and full-frame geometry regressions.
- Installed the final APK on an isolated API 35 emulator with simulated front/back cameras. A saved front-camera setting of 60 fps prompts before starting; the picker offers only 30 fps on this camera. Explicitly accepting **Use 30 fps** starts the server.
- Three consecutive 2560×1440 requests close with zero response bytes. The phone retains the actual unsupported-resolution error and camera-reported alternatives across retries. Visually checked the error screen in `emulator-capture-error.png`.
- At 20 Mbps the emulator rejects H.264 encoding at 1080p and 720p. The phone displays the encoder error; no bogus `0xffffffff` packet is sent. This is a limit of the test encoder, not evidence of a 20 Mbps limit on the Xiaomi 13T.
- After explicitly changing to 12 Mbps, a rejected 1440p request followed by **1280×720 / 30 fps** recovers on the same running server. **120 video and 120 audio access units** pass FFmpeg decoding; the original unmodified OBS decoder consumes **120 video and 119 audio frames** (its usual AAC bootstrap discard). The error clears after successful capture, and Stop returns the UI to idle.
- APK v2 signature and SHA-256 checksum verified. Physical Xiaomi 13T validation remains necessary; the capability checks use Android's reported values and do not override hardware limits.

## Version 0.1.2 — preserve the landscape field of view

- Removed 0.1.1's portrait-to-landscape center crop. Instead, quarter-turned camera texture axes are rotated back into landscape while preserving the complete valid buffer rectangle and the producer's texture-origin correction. Upright phones intentionally show sideways video; hold the phone horizontally for landscape use.
- **84 host assertions pass**: protocol/network tests plus all four scene corners, sampled area, handedness and landscape axes across eight rotation/reflection combinations with realistic buffer insets.
- Build and lint pass (0 errors, 7 existing warnings).
- Final APK emulator capture: front camera 1280×720, **300 video / 300 audio access units** decoded by FFmpeg, including background/resume; rear camera 640×480, **120 video / 120 audio access units** decoded.
- The original OBS decoder decoded the front capture: **300 video / 299 audio frames** (AAC bootstrap discards one frame).
- Visually inspected the phone preview and decoded video: the sideways image exposes the scene that 0.1.1's extra crop removed. Physical-phone field-of-view confirmation remains necessary; the camera's native sensor-aspect crop and user-selected zoom still apply.
- The emulator exited after the successful media captures, before the final automated UI-bounds assertion could run. This does not count as a passed end-to-end UI test; the host geometry checks and media-decoding results above completed independently.

## Version 0.1.1 — aspect ratio and front-camera transform

- Replaced the independent 640×480 preview and direct camera-to-encoder path with one GPU renderer. Both destinations apply the same `SurfaceTexture` matrix and aspect-preserving crop. This bakes the camera producer's vertical inversion/rotation into pixels rather than relying on preview-only metadata.
- The preview layout now follows the OBS resolution. Stop hides the texture and covers it with an opaque idle view, so the last captured image does not remain visible.
- Build and lint pass (0 errors, 7 existing warnings). **30 protocol, socket and geometry assertions pass**, including 16:9/4:3 layout and rotated-buffer crop regressions.
- Final APK emulator regression: front camera **1280×720**, 450 video and 450 audio access units decoded by FFmpeg, including background/resume; rear camera **640×480**, 120 video and 120 audio access units decoded. The 4:3 layout was checked against UI bounds for that explicitly requested format.
- The original plugin decoder consumed the final front-camera capture: **450 video / 449 audio frames**, the expected AAC bootstrap difference.
- Visually compared front-camera preview with decoded H.264: matching top/bottom orientation and crop. Verified Stop hides the frame. No OpenCam/Android runtime errors were logged in the final regression run.
- This validates the correction on the emulator. Confirmation on the reporting phone is still needed; remove any manually applied OBS flip before comparing version 0.1.1.

The results below describe the initial 0.1.0 baseline.

## Build and host tests

- Gradle 9.1.0 / Android Gradle Plugin 9.0.1 / OpenJDK 21 / Android SDK 36: `assembleDebug` passed.
- `lintDebug`: **0 errors, 7 warnings**. Warnings concern the pinned tool version, API 35 target, backup metadata, explicit foreground-service wake-lock lifetime, and English-only UI strings. They are not suppressed globally.
- `tools/test-protocol.sh`: **26 passing assertions**. Covers the exact big-endian envelope, config sentinel, malformed/oversized/truncated requests, bare requests without newline, bytewise-fragmented TCP, simultaneous video/audio, rejection of a second video consumer, persistent tally/battery control sockets, blocked-writer timeout, and server shutdown closing clients.

## Android emulator

API 35 x86_64, simulated rear camera, installed and launched from the built APK. The emulator has a 720p camera and AVC encoder; it rejects 1080p capture and has no suitable HEVC encoder. Those requests report a useful error instead of crashing.

- H.264 at **1280×720 / 30 fps**, optional **48 kHz mono AAC**: 120 video and 120 audio access units captured with the actual plugin request strings and decoded by command-line FFmpeg without errors.
- Captured raw `.wire` streams fed through the sibling plugin's **unmodified `src/ffmpeg_decode.cc`**, compiled with the host OBS and FFmpeg libraries: **120 video frames and 119 audio frames decoded**. The one-frame audio difference is the plugin's intentional config/bootstrap discard.
- Repeated connections on the same running server succeed.
- A longer **600-frame video and 600-access-unit audio** capture passes, including moving the activity to the background during streaming. Preview and encoder session changes did not interrupt the captured bitstream.
- OBS tally `program` updates the on-air indicator. Live preview and frame/bitrate indicators were visually inspected on the emulator.
- The final delivered APK was reinstalled and retested: 120 video / 120 audio access units passed FFmpeg, and 120 video / 119 audio frames passed the original receiver decoder. APK v2 signature and SHA-256 checksum verified.
- After Stop, Android reports no active camera clients and the service is no longer foreground or start-requested (the visible activity can retain an idle service binding).

## What this does not prove

This is an emulator and receiver-decoder interoperability test, **not a full physical-phone/OBS Studio acceptance test**. A real device is still needed to validate its camera-specific behavior, Wi-Fi discovery, USB permissions, torch, focus, image quality, thermal throttling, screen-lock behavior under vendor battery policies, 60 fps, 4K and HEVC. There are no latency or quality comparisons against the proprietary DroidCam Android app.

For acceptance on a phone, connect it to OBS, run a sustained recording, check sync/rotation, background and lock/unlock the phone, reconnect after changing scenes, then confirm that Stop releases camera and microphone. The included probe and receiver harness make regressions reproducible.
