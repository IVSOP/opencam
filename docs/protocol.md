# Receiver-derived protocol

Reference: sibling `droidcam-obs-plugin`, commit `ddfa8880451f088e19b0358635922cf000bcda6d` (plugin version string `251`). This is an interoperability implementation of what that receiver consumes, not a claim to implement every endpoint of the original phone app.

## Transport and requests

TCP port 4747. Wi-Fi uses the phone address; USB uses ordinary ADB `forward tcp:LOCAL tcp:4747`. DNS-SD service `_droidcamobs._tcp.local.`, TXT attribute `name` supplies the human-readable label. Android NSD adds `.local.` to the registered `_droidcamobs._tcp.` type.

`src/plugin_properties.h` defines these exact media requests:

```text
GET /v5/video/avc/1920x1080/port/0/os/Linux/obs/32.0/client/251/hdr/0/nonce/5912/
GET /v2/audio
```

These are **not complete HTTP requests**: there is no terminating newline or header block. The media reply starts immediately with binary framing, not an HTTP status line. Parsing must tolerate fragmented TCP reads and know when the bare request ends. The final `/nonce/<integer>/` terminates video requests. OS/version path fields are metadata; no authentication challenge was present in the receiver.

`avc` selects H.264, `hevc` selects H.265, and `jpg` would select MJPEG. OpenCam explicitly rejects `jpg` and `/hdr/1/`. Resolution support is checked against Camera2 and MediaCodec; it never silently substitutes a size.

## Packet format

From `src/source.cc::read_frame` and `src/plugin.h`:

| Offset | Size | Value |
| --- | --- | --- |
| 0 | 8 bytes | Unsigned big-endian presentation timestamp, **microseconds** |
| 8 | 4 bytes | Unsigned big-endian payload length |
| 12 | length bytes | Encoded access unit or codec configuration |

`0xffffffffffffffff` as the timestamp marks a codec-config packet. Configuration is at most **1024 bytes**, access units at most **16 MiB**, and zero-length payloads are invalid. The receiver prepends a config packet to the next access unit. Sending two config packets before a media packet is rejected by the receiver, so OpenCam coalesces video CSD and deduplicates format-change vs. codec-config-buffer notifications.

Video: MediaCodec emits AVC SPS/PPS (or HEVC VPS/SPS/PPS) and Annex-B access units. One encoded output buffer becomes one wire packet. Camera timestamps are forwarded through EGL presentation timestamps in microseconds. Since 0.1.1, camera texture transforms are baked into the encoded pixels with OpenGL; raw H.264 does not convey the phone preview’s transform flags. Since 0.1.2, both destinations render the full landscape camera buffer: a quarter-turn axis swap is removed by rotating UV coordinates, without the former portrait-to-landscape center crop. No B frames are requested; AVC uses Baseline profile for predictable low-delay decoding. The receiver multiplies timestamps by 1000 for OBS.

Audio: an MPEG-4 AudioSpecificConfig is followed by raw AAC-LC access units, **without ADTS**. At 48 kHz mono the common ASC is `11 88`. The receiver initializes AAC using the leading config and discards the first associated audio access unit; subsequent raw frames are decoded. Its audio output uses the desktop clock rather than the packet PTS. `tools/probe.py` adds ADTS only to its saved `.aac` file for command-line FFmpeg; its `.wire` file preserves the real stream.

On rejection, OpenCam 0.1.3 closes the media socket without sending a fabricated packet. The actual capture error remains visible on the phone across retries, until successful capture or Stop. The receiver can report EOF/disconnection and reconnect normally.

Versions through 0.1.2 sent a 12-byte packet with length `0xffffffff` on failure. That produced the misleading OBS warning `packet too large/empty: 4294967295`; it was not an actual oversized video frame. The receiver's comparison of a 32-bit length against the 64-bit `NO_PTS` sentinel does not match, so its size guard rejected the packet. There is no usable structured error response in this media protocol.

## Control connection

Unlike media, tally and battery use ordinary HTTP-like headers:

```text
PUT /v1/tally/program/ HTTP/1.1\r\n\r\n
PUT /v1/tally/preview/ HTTP/1.1\r\n\r\n
PUT /v1/tally/idle/ HTTP/1.1\r\n\r\n
GET /battery HTTP/1.1\r\n\r\n
```

Replies have a status line, content length, CRLF header separator and a short text body. Battery body is a decimal percentage. The connection stays open because `source.cc::basic_http` reads until its one-second timeout and treats EOF as failure. OpenCam allows 65 seconds of idle time on an established control connection.

`GET /` serves a small connection guide. The unused `PING_REQ` constant has a simple `DroidCam` response; no relied-upon discovery or video behavior depends on that response. Proprietary web camera-control endpoints are not implemented.

## Resource behavior

At most eight client handlers, no queued client tasks, one camera consumer and one microphone consumer. Writes are synchronous; a watchdog closes a socket whose current packet write exceeds three seconds (checked once per second). The encoder drains on its own worker thread, independent of the Android main thread. Closing the server shuts down the listening socket, all accepted connections and workers. No unbounded frame queue is maintained by the app.
