#!/bin/sh
set -eu
if [ "$#" -ne 3 ]; then
    echo "Usage: $0 /path/to/droidcam-obs-plugin avc\|hevc\|audio /path/to/capture.wire" >&2
    exit 2
fi
plugin_dir=$(cd "$1" && pwd)
mode=$2
capture=$3
app_dir=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
# OBS headers commonly live in /usr/include/obs; override for other SDK layouts.
c++ -std=c++17 -I"$plugin_dir/src" -I"${OBS_INCLUDE_DIR:-/usr/include/obs}" \
    "$app_dir/tests/receiver_compat.cc" "$plugin_dir/src/ffmpeg_decode.cc" \
    -o "$test_dir/receiver-test" $(pkg-config --libs libavcodec libavutil libobs)
"$test_dir/receiver-test" "$mode" "$capture"
