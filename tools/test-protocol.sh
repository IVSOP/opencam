#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_classes=$(mktemp -d)
trap 'rm -rf "$test_classes"' EXIT
javac -d "$test_classes" app/src/main/java/dev/opencam/obs/Protocol.java app/src/main/java/dev/opencam/obs/StreamServer.java app/src/main/java/dev/opencam/obs/FrameGeometry.java app/src/main/java/dev/opencam/obs/CameraRates.java tests/ProtocolTest.java
java -cp "$test_classes" dev.opencam.obs.ProtocolTest
