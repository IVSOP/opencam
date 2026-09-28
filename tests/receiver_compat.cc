// Compile with the original plugin's ffmpeg_decode.cc: exercise its real decoder, unmodified.
#include "plugin.h"
#include "ffmpeg_decode.h"
#include <fstream>
#include <iostream>
#include <vector>
#include <cstring>

int main(int argc, char** argv) {
    if (argc != 3) { std::cerr << "usage: receiver-test avc|hevc|audio capture.wire\n"; return 2; }
    bool audio = std::string(argv[1]) == "audio";
    AVCodecID codec = audio ? AV_CODEC_ID_AAC : std::string(argv[1]) == "hevc" ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264;
    std::ifstream input(argv[2], std::ios::binary);
    if (!input) return 2;
    FFMpegDecoder decoder;
    obs_source_frame2 video{};
    obs_source_audio sound{};
    std::vector<uint8_t> config;
    int frames = 0;
    while (true) {
        uint8_t header[12];
        if (!input.read(reinterpret_cast<char*>(header), 12)) break;
        uint64_t pts = 0; uint32_t len = 0;
        for (int i = 0; i < 8; i++) pts = (pts << 8) | header[i];
        for (int i = 8; i < 12; i++) len = (len << 8) | header[i];
        if (!len || len > 16 * 1024 * 1024) return 3;
        std::vector<uint8_t> payload(len);
        if (!input.read(reinterpret_cast<char*>(payload.data()), len)) return 3;
        if (pts == NO_PTS) {
            if (!config.empty() || len > 1024) return 3;
            config = payload;
            continue;
        }
        DataPacket packet(config.size() + len + AV_INPUT_BUFFER_PADDING_SIZE);
        memset(packet.data, 0, packet.size);
        if (!config.empty()) memcpy(packet.data, config.data(), config.size());
        memcpy(packet.data + config.size(), payload.data(), len);
        packet.used = config.size() + len;
        packet.pts = pts;
        if (!decoder.ready) {
            if (decoder.init(audio ? packet.data : nullptr, codec, false) < 0) return 4;
            if (audio) { config.clear(); continue; } // Exactly source.cc's AAC bootstrap behavior.
        }
        config.clear();
        bool got = false;
        bool success = audio ? decoder.decode_audio(&sound, &packet, &got) : decoder.decode_video(&video, &packet, &got);
        if (!success) { std::cerr << "Receiver decoder failed\n"; return 5; }
        if (got) frames++;
    }
    if (!frames) return 6;
    std::cout << "PASS: original plugin decoded " << frames << (audio ? " audio frames" : " video frames") << '\n';
    return 0;
}
