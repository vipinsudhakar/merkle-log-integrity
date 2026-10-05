package com.merklelog.benchmark;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mean, sample standard deviation, min and max of a list of measurements.
 *
 * @param mean the arithmetic mean (NaN for an empty list)
 * @param sd   the sample standard deviation, dividing by {@code n − 1} (0 for fewer than 2 values)
 * @param min  the smallest value
 * @param max  the largest value
 */
record Stats(double mean, double sd, double min, double max) {

    static Stats of(List<Double> values) {
        if (values.isEmpty()) {
            return new Stats(Double.NaN, 0, Double.NaN, Double.NaN);
        }
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double mean = sum / values.size();
        double squares = 0;
        for (double v : values) {
            squares += (v - mean) * (v - mean);
        }
        double sd = values.size() < 2 ? 0 : Math.sqrt(squares / (values.size() - 1));
        return new Stats(mean, sd, min, max);
    }

    /** {@code {mean, sd}} for timed metrics. */
    Map<String, Object> map() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mean", mean);
        m.put("sd", sd);
        return m;
    }

    /** {@code {mean, sd, min, max}} for distributions. */
    Map<String, Object> withMinMax() {
        Map<String, Object> m = map();
        m.put("min", min);
        m.put("max", max);
        return m;
    }
}
