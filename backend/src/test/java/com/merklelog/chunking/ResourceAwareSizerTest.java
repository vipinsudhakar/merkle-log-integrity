package com.merklelog.chunking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The base paper's Eq. 1 and Eq. 2 (Yağız et al. 2026, §3.4), pinned to hand-computed values.
 *
 * <p>Both the baseline and CAAC size their chunks with this rule, so if it drifted from the
 * paper, the comparison would no longer be against the paper's method.
 */
@DisplayName("ResourceAwareSizer — the paper's Eq. 1–2")
class ResourceAwareSizerTest {

    @ParameterizedTest(name = "P = {0} -> A = {1}")
    @CsvSource({
            "0.95, 0.8",
            "0.81, 0.8",
            "0.80, 0.9",   // P > 0.8 is strict, so 0.8 itself falls to the next band
            "0.61, 0.9",
            "0.60, 1.0",   // likewise P > 0.6 is strict
            "0.45, 1.0",
            "0.30, 1.0",   // P < 0.3 is strict
            "0.29, 1.1",
            "0.00, 1.1",
    })
    @DisplayName("Eq. 2: the adjustment factor bands, including their strict boundaries")
    void adjustmentFactorBands(double pressure, double expected) {
        assertThat(ResourceAwareSizer.adjustmentFactor(pressure)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Eq. 1 then Eq. 2 at the paper's baseline pressure: 70 entries with the defaults")
    void baselinePressure() {
        // M_avail = 1024 * (1 - 0.25) = 768;  floor(768 * 0.5 / 6) = 64;  A(0.25) = 1.1;  floor(70.4) = 70
        assertThat(new ResourceAwareSizer().chunkSize(0.25)).isEqualTo(70);
    }

    @Test
    @DisplayName("Eq. 1 then Eq. 2 at the paper's stress pressure: 9 entries with the defaults")
    void stressPressure() {
        // M_avail = 1024 * 0.15 = 153.6;  floor(153.6 * 0.5 / 6) = 12;  A(0.85) = 0.8;  floor(9.6) = 9
        assertThat(new ResourceAwareSizer().chunkSize(0.85)).isEqualTo(9);
    }

    @Test
    @DisplayName("higher pressure never gives a larger chunk")
    void sizeShrinksAsPressureRises() {
        ResourceAwareSizer sizer = new ResourceAwareSizer();
        int previous = Integer.MAX_VALUE;
        for (int percent = 0; percent <= 100; percent++) {
            int size = sizer.chunkSize(percent / 100.0);
            assertThat(size).as("P = %d%%", percent).isLessThanOrEqualTo(previous);
            previous = size;
        }
    }

    @Test
    @DisplayName("the result is clamped to [C_min, C_max] even after the adjustment factor")
    void clampedToBounds() {
        ResourceAwareSizer sizer = new ResourceAwareSizer(1024, 0.5, 6, 20, 40);

        assertThat(sizer.chunkSize(0.0)).isEqualTo(40);   // Eq. 1 alone would give 85
        assertThat(sizer.chunkSize(1.0)).isEqualTo(20);   // no free memory at all
    }

    @Test
    @DisplayName("invalid parameters are rejected")
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new ResourceAwareSizer(0, 0.5, 6, 8, 256)).hasMessageContaining("totalMemory");
        assertThatThrownBy(() -> new ResourceAwareSizer(1024, 1.0, 6, 8, 256)).hasMessageContaining("targetUtilisation");
        assertThatThrownBy(() -> new ResourceAwareSizer(1024, 0.5, 0, 8, 256)).hasMessageContaining("scalingK");
        assertThatThrownBy(() -> new ResourceAwareSizer(1024, 0.5, 6, 0, 256)).hasMessageContaining("minChunk");
        assertThatThrownBy(() -> new ResourceAwareSizer(1024, 0.5, 6, 50, 10)).hasMessageContaining("maxChunk");
        assertThatThrownBy(() -> new ResourceAwareSizer().chunkSize(1.5)).hasMessageContaining("pressure");
    }
}
