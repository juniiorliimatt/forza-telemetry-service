package br.com.forza.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PerformanceClassTest {

    /** Faixas do FH6 (forzahorizonhub.com): D 100–400, C 401–500, B 501–600, A 601–700, S1 701–800, S2 801–900, R 901–998. */
    @ParameterizedTest
    @CsvSource({
            "100,D", "400,D", "401,C", "500,C", "501,B", "600,B", "601,A", "700,A",
            "701,S1", "800,S1", "801,S2", "900,S2", "901,R", "998,R"})
    void of_mapsEachPerformanceIndexToItsClass(final int pi, final PerformanceClass expected) {
        assertThat(PerformanceClass.of(pi)).isEqualTo(expected);
    }

    @Test
    void of_outOfRangeValuesAreClampedToTheNearestClass() {
        assertThat(PerformanceClass.of(0)).isEqualTo(PerformanceClass.D);
        assertThat(PerformanceClass.of(-5)).isEqualTo(PerformanceClass.D);
        assertThat(PerformanceClass.of(999)).isEqualTo(PerformanceClass.R);
        assertThat(PerformanceClass.of(5000)).isEqualTo(PerformanceClass.R);
    }

    @Test
    void ranges_coverEveryPerformanceIndexExactlyOnce() {
        for (int pi = 0; pi <= 1100; pi++) {
            final int value = pi;
            final var matches = java.util.Arrays.stream(PerformanceClass.values()).filter(c -> value >= c.minPi() && value <= c.maxPi()).toList();
            assertThat(matches).as("PI %d", pi).hasSize(1);
            assertThat(matches.get(0)).isEqualTo(PerformanceClass.of(pi));
        }
    }

    @Test
    void sameClass_isTheSeparationUsedForPiChangesInsideTheSameBuildTier() {
        assertThat(PerformanceClass.of(690)).isEqualTo(PerformanceClass.of(700));
        assertThat(PerformanceClass.of(700)).isNotEqualTo(PerformanceClass.of(701));
    }
}
