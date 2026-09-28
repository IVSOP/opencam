package dev.opencam.obs;

/** Keep the full camera buffer in landscape, identically for OBS and the preview. */
final class FrameGeometry {
    private FrameGeometry() {}
    static void landscape(float[] camera) {
        boolean swapped = Math.abs(camera[1]) > Math.abs(camera[0]);
        if (!swapped) return;
        // Compose T with (u,v) -> (v,1-u), a quarter turn around the texture center.
        // This removes the portrait axis swap without discarding any part of the buffer.
        // Retain the producer's texture-origin correction, reflection and valid-pixel inset.
        for (int row = 0; row < 4; row++) {
            float x = camera[row], y = camera[4 + row];
            camera[row] = -y;
            camera[4 + row] = x;
            camera[12 + row] += y;
        }
    }
    static int previewHeight(int width, int streamWidth, int streamHeight) {
        return Math.max(1,Math.round((float)width*streamHeight/streamWidth));
    }
}
