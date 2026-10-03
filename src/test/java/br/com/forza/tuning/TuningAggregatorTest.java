package br.com.forza.tuning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import br.com.forza.models.dto.TuningSummaryDTO;
import br.com.forza.models.dto.TuningSummaryDTO.Braking;
import br.com.forza.models.dto.TuningSummaryDTO.CornerBalance;
import br.com.forza.models.dto.TuningSummaryDTO.CornerPhase;
import br.com.forza.models.dto.TuningSummaryDTO.Engine;
import br.com.forza.models.dto.TuningSummaryDTO.Gear;
import br.com.forza.models.dto.TuningSummaryDTO.SpeedKmh;
import br.com.forza.models.dto.TuningSummaryDTO.SuspensionWheel;
import br.com.forza.models.dto.TuningSummaryDTO.TireWheel;
import br.com.forza.models.dto.TuningSummaryDTO.Traction;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TuningAggregatorTest {

    private final TuningAggregator aggregator = new TuningAggregator();

    private static Map<String, SuspensionWheel> susp(final double bottoming) {
        final var w = new SuspensionWheel(0.5, 0.8, bottoming, 0.0);
        return Map.of("FL", w, "FR", w, "RL", w, "RR", w);
    }

    private static Map<String, TireWheel> tires(final double temp) {
        final var w = new TireWheel(temp, temp + 10, null);
        return Map.of("FL", w, "FR", w, "RL", w, "RR", w);
    }

    private static TuningSummaryDTO summary(final int samples, final double bottoming, final double temp, final double entryUnder,
                                            final long entrySamples, final double topSpeed) {
        return new TuningSummaryDTO(samples, samples / 20.0, susp(bottoming), new SpeedKmh(topSpeed, 100.0),
                new Engine(8000, 500, 7000, 600, 5000, 10, Map.of("6", new Gear(50.0, 6000, 10.0))),
                tires(temp),
                new CornerBalance(new CornerPhase((int) entrySamples, 0.5, 0.5, entryUnder, 0.0), new CornerPhase(0, null, null, null, null),
                        new CornerPhase(0, null, null, null, null), null),
                new Braking(100, 4.0, 2.0), new Traction(200, 10.0, Map.of("1", 20.0)), null, 2.0);
    }

    @Test
    void sums_sessionsAndSamples() {
        final var agg = aggregator.aggregate(List.of(
                new TuningAggregator.SessionSummary(1000, summary(1000, 0, 190, 10, 100, 250)),
                new TuningAggregator.SessionSummary(3000, summary(3000, 0, 190, 10, 100, 280))));

        assertThat(agg.sessions()).isEqualTo(2);
        assertThat(agg.samples()).isEqualTo(4000);
    }

    @Test
    void weightsSessionMetricsBySampleCount_notASimpleAverage() {
        final var agg = aggregator.aggregate(List.of(
                new TuningAggregator.SessionSummary(1000, summary(1000, 10.0, 160, 10, 100, 250)),
                new TuningAggregator.SessionSummary(3000, summary(3000, 2.0, 200, 10, 100, 250))));

        assertThat(agg.bottomingPct().get("FL")).isCloseTo(4.0, within(1e-9));
        assertThat(agg.tireTempF().get("RR")).isCloseTo(190.0, within(1e-9));
    }

    @Test
    void weightsCornerPhasesByTheirOwnSampleCounts_andSumsThem() {
        final var agg = aggregator.aggregate(List.of(
                new TuningAggregator.SessionSummary(1000, summary(1000, 0, 190, 80.0, 300, 250)),
                new TuningAggregator.SessionSummary(1000, summary(1000, 0, 190, 20.0, 100, 250))));

        assertThat(agg.entry().samples()).isEqualTo(400);
        assertThat(agg.entry().understeerPct()).isCloseTo(65.0, within(1e-9));
        assertThat(agg.mid().samples()).isZero();
        assertThat(agg.mid().understeerPct()).isZero();
    }

    @Test
    void keepsTheMaximumTopSpeed() {
        final var agg = aggregator.aggregate(List.of(
                new TuningAggregator.SessionSummary(1000, summary(1000, 0, 190, 10, 100, 250)),
                new TuningAggregator.SessionSummary(1000, summary(1000, 0, 190, 10, 100, 301.5))));

        assertThat(agg.topSpeedKmh()).isEqualTo(301.5);
    }

    @Test
    void skipsSessionsWithoutSuspensionData_theyCarryNoMetrics() {
        final var empty = new TuningSummaryDTO(0, 0.0, null, null, null, null, null, null, null, null, null);

        final var agg = aggregator.aggregate(List.of(
                new TuningAggregator.SessionSummary(0, empty),
                new TuningAggregator.SessionSummary(2000, summary(2000, 6.0, 190, 10, 100, 250))));

        assertThat(agg.sessions()).isEqualTo(1);
        assertThat(agg.bottomingPct().get("FL")).isEqualTo(6.0);
    }

    @Test
    void noUsableSessions_returnsAnEmptyAggregate() {
        final var agg = aggregator.aggregate(List.of());

        assertThat(agg.sessions()).isZero();
        assertThat(agg.samples()).isZero();
        assertThat(agg.bottomingPct()).isEmpty();
    }
}
