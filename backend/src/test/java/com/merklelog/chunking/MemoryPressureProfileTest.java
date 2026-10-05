package com.merklelog.chunking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MemoryPressureProfile — the simulated pressure signal")
class MemoryPressureProfileTest {

    @Test
    @DisplayName("each window of entries gets its own pressure; past the end the last value holds")
    void pressureByWindow() {
        MemoryPressureProfile profile = new MemoryPressureProfile(List.of(0.1, 0.9, 0.5), 100);

        assertThat(profile.pressureAt(0)).isEqualTo(0.1);
        assertThat(profile.pressureAt(99)).isEqualTo(0.1);
        assertThat(profile.pressureAt(100)).isEqualTo(0.9);
        assertThat(profile.pressureAt(250)).isEqualTo(0.5);
        assertThat(profile.pressureAt(1_000_000)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("the paper's stress test: baseline, three stress windows of 2000 entries, recovery")
    void paperStressTest() {
        MemoryPressureProfile profile = MemoryPressureProfile.paperStressTest();

        assertThat(profile.windowEntries()).isEqualTo(2000);
        assertThat(profile.pressureAt(0)).isEqualTo(0.25);
        assertThat(profile.pressureAt(2000)).isEqualTo(0.85);
        assertThat(profile.pressureAt(7999)).isEqualTo(0.85);
        assertThat(profile.pressureAt(8000)).isEqualTo(0.25);
    }

    @Test
    @DisplayName("parse and describe round-trip")
    void parseRoundTrips() {
        MemoryPressureProfile profile = MemoryPressureProfile.parse(" 0.25, 0.85 ,0.25", 500);

        assertThat(profile.pressures()).containsExactly(0.25, 0.85, 0.25);
        assertThat(MemoryPressureProfile.parse(profile.describe(), 500)).isEqualTo(profile);
    }

    @Test
    @DisplayName("out-of-range or malformed values are rejected")
    void rejectsInvalidProfiles() {
        assertThatThrownBy(() -> MemoryPressureProfile.parse("0.2,1.4", 10)).hasMessageContaining("[0, 1]");
        assertThatThrownBy(() -> MemoryPressureProfile.parse("0.2,x", 10)).hasMessageContaining("x");
        assertThatThrownBy(() -> new MemoryPressureProfile(List.of(), 10)).hasMessageContaining("at least one");
        assertThatThrownBy(() -> new MemoryPressureProfile(List.of(0.5), 0)).hasMessageContaining("windowEntries");
        assertThatThrownBy(() -> MemoryPressureProfile.baseline().pressureAt(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
