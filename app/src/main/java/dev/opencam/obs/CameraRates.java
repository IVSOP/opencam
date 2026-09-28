package dev.opencam.obs;

import java.util.ArrayList;
import java.util.List;

/** An offered rate must fit an AE range and at least one normal capture size. */
final class CameraRates {
    private CameraRates() {}
    static boolean contains(int[][] ranges, int rate) {
        for (int[] range : ranges) if (range[0] <= rate && rate <= range[1]) return true;
        return false;
    }
    static boolean durationAllows(long durationNs, int rate) {
        return durationNs == 0 || durationNs <= 1_000_000_000L / rate + 1_000_000;
    }
    static int[] offered(int[][] ranges, long[] durationsNs) {
        List<Integer> result = new ArrayList<>();
        for (int rate : new int[]{30, 60}) {
            if (!contains(ranges, rate)) continue;
            for (long duration : durationsNs) if (durationAllows(duration, rate)) {
                result.add(rate); break;
            }
        }
        int[] values = new int[result.size()];
        for (int i = 0; i < values.length; i++) values[i] = result.get(i);
        return values;
    }
}
