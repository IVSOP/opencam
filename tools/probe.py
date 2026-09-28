#!/usr/bin/env python3
"""Capture and decode real phone output using exactly the plugin's bare requests/framing."""
import argparse
import concurrent.futures
import pathlib
import socket
import struct
import subprocess


def receive(sock, size):
    data = bytearray()
    while len(data) < size:
        block = sock.recv(size - len(data))
        if not block:
            raise EOFError(f"Stream ended after {len(data)}/{size} bytes")
        data.extend(block)
    return bytes(data)


def capture(args, audio=False):
    request = "GET /v2/audio" if audio else (
        f"GET /v5/video/{args.codec}/{args.size}/port/0/os/Linux/obs/32.0/client/2.4/hdr/0/nonce/5912/"
    )
    target = args.output / ("audio.aac" if audio else "video.h265" if args.codec == "hevc" else "video.h264")
    config = None
    frames, last_pts = 0, -1
    with socket.create_connection((args.host, args.port), timeout=15) as sock, target.open("wb") as out, target.with_suffix(".wire").open("wb") as wire:
        sock.sendall(request.encode("ascii"))
        while frames < args.frames:
            pts, length = struct.unpack(">QI", receive(sock, 12))
            if not 0 < length <= 16 * 1024 * 1024:
                raise ValueError(f"App rejected request or invalid packet size: {length}; check phone status")
            payload = receive(sock, length)
            wire.write(struct.pack(">QI", pts, length))
            wire.write(payload)
            if pts == 0xFFFFFFFFFFFFFFFF:
                if config is not None or length > 1024:
                    raise ValueError("Duplicate/oversized codec config before first access unit")
                config = payload
                if not audio:
                    out.write(payload)
                continue
            if config is None or pts < last_pts:
                raise ValueError("Missing config or non-monotonic timestamp")
            last_pts = pts
            if audio:
                # The plugin initializes its raw AAC decoder from this AudioSpecificConfig.
                # For CLI ffmpeg only, wrap each raw access unit in an ADTS header.
                profile = (config[0] >> 3) - 1
                freq = ((config[0] & 7) << 1) | (config[1] >> 7)
                channels = (config[1] >> 3) & 15
                n = len(payload) + 7
                out.write(bytes([0xFF, 0xF1, (profile << 6) | (freq << 2) | (channels >> 2),
                                 ((channels & 3) << 6) | (n >> 11), (n >> 3) & 255,
                                 ((n & 7) << 5) | 31, 0xFC]))
            out.write(payload)
            frames += 1
    subprocess.run(["ffmpeg", "-v", "error", "-xerror", "-i", str(target), "-f", "null", "-"], check=True)
    print(f"PASS: {frames} {'audio' if audio else 'video'} access units received and decoded: {target}")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("host")
    p.add_argument("--port", type=int, default=4747)
    p.add_argument("--size", default="1920x1080")
    p.add_argument("--codec", choices=["avc", "hevc"], default="avc")
    p.add_argument("--frames", type=int, default=120)
    p.add_argument("--audio", action="store_true")
    p.add_argument("--output", type=pathlib.Path, default=pathlib.Path("captures"))
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(capture, args)]
        if args.audio:
            futures.append(pool.submit(capture, args, True))
        for future in futures:
            future.result()
