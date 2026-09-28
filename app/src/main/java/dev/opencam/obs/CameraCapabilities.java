package dev.opencam.obs;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;
import java.util.*;

final class CameraCapabilities {
    static final int[][] OBS_SIZES = {{640,480},{1024,768},{1280,720},{1920,1080},{1920,1440},{2560,1440},{3840,2160}};
    private CameraCapabilities() {}
    static int[][] ranges(CameraCharacteristics c) {
        Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return new int[0][];
        int[][] values = new int[ranges.length][2];
        for (int i=0; i<ranges.length; i++) {
            values[i][0] = ranges[i].getLower(); values[i][1] = ranges[i].getUpper();
        }
        return values;
    }
    static int[] frameRates(Context context, String camera) throws CameraAccessException {
        CameraCharacteristics c = context.getSystemService(CameraManager.class).getCameraCharacteristics(camera);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null) return new int[0];
        List<Long> durations = new ArrayList<>();
        for (Size size : sizes) {
            boolean obsSize = false;
            for (int[] pair : OBS_SIZES) if (pair[0] == size.getWidth() && pair[1] == size.getHeight()) obsSize = true;
            if (obsSize) durations.add(map.getOutputMinFrameDuration(SurfaceTexture.class,size));
        }
        long[] ns = new long[durations.size()];
        for (int i=0; i<ns.length; i++) ns[i] = durations.get(i);
        return CameraRates.offered(ranges(c),ns);
    }
    static String alternatives(CameraCharacteristics c, StreamConfigurationMap map) {
        Size[] available = map.getOutputSizes(SurfaceTexture.class);
        if (available == null) return "Check the selected camera's video capabilities.";
        List<Size> sizes = Arrays.asList(available);
        int[][] ranges = ranges(c);
        StringBuilder result = new StringBuilder("Camera reports these OBS modes:");
        for (int rate : new int[]{30,60}) {
            if (!CameraRates.contains(ranges,rate)) continue;
            StringJoiner list = new StringJoiner(", ");
            for (int[] pair : OBS_SIZES) {
                Size size = new Size(pair[0],pair[1]);
                if (sizes.contains(size) && CameraRates.durationAllows(map.getOutputMinFrameDuration(SurfaceTexture.class,size),rate))
                    list.add(size.toString());
            }
            if (list.length()>0) result.append("\n").append(rate).append(" fps: ").append(list);
        }
        return result.toString();
    }
}
