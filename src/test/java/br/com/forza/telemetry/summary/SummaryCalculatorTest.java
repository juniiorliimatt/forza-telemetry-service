package br.com.forza.telemetry.summary;

import static br.com.forza.support.Fixtures.sample;
import static br.com.forza.support.Fixtures.session;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.forza.models.entities.LapRecord;
import br.com.forza.models.entities.SampleRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SummaryCalculatorTest {

    private final SummaryCalculator calculator = new SummaryCalculator();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(final Map<String, Object> parent, final String key) {
        return (Map<String, Object>) parent.get(key);
    }

    private Map<String, Object> summarize(final int drivetrain, final List<SampleRow> rows) {
        return calculator.calculate(session(drivetrain), rows, List.of());
    }

    @Test
    void calculate_noSamples_returnsOnlyCountAndZeroDuration() {
        final var out = summarize(1, List.of());

        assertThat(out).containsOnlyKeys("samples", "durationS");
        assertThat(out.get("samples")).isEqualTo(0);
        assertThat(out.get("durationS")).isEqualTo(0.0);
    }

    @Test
    void calculate_reportsSampleCountAndDurationFromLastSample() {
        final var out = summarize(1, List.of(sample().tMs(0).build(), sample().tMs(1500).build(), sample().tMs(12_340).build()));

        assertThat(out.get("samples")).isEqualTo(3);
        assertThat(out.get("durationS")).isEqualTo(12.3);
    }

    @Test
    void calculate_speed_convertsToKmhAndAveragesOnlyMovingSamples() {
        final var out = summarize(1, List.of(
                sample().speed(5f).build(),
                sample().speed(20f).build(),
                sample().speed(30f).build()));

        final var speed = map(out, "speedKmh");
        assertThat(speed.get("max")).isEqualTo(108.0);
        assertThat(speed.get("meanMoving")).isEqualTo(90.0);
    }

    @Test
    void calculate_suspension_reportsBottomingToppingMeanAndP95() {
        final var rows = List.of(
                sample().susp(0.99f, 0.5f, 0.5f, 0.5f).build(),
                sample().susp(0.5f, 0.5f, 0.5f, 0.5f).build(),
                sample().susp(0.01f, 0.5f, 0.5f, 0.5f).build(),
                sample().susp(0.5f, 0.5f, 0.5f, 0.5f).build());

        final var fl = map(map(summarize(1, rows), "suspension"), "FL");

        assertThat(fl.get("bottomingPct")).isEqualTo(25.0);
        assertThat(fl.get("toppingPct")).isEqualTo(25.0);
        assertThat(fl.get("mean")).isEqualTo(0.5);
        assertThat(fl.get("p95")).isEqualTo(0.99);
        assertThat(map(map(summarize(1, rows), "suspension"), "RR").get("bottomingPct")).isEqualTo(0.0);
    }

    @Test
    void calculate_engine_reportsPeaksAndLimiterWithThrottlePerGear() {
        final var rows = List.of(
                sample().gear(3).rpm(6000f).power(100_000f).torque(300f).boost(5f).build(),
                sample().gear(3).rpm(7800f).power(200_000f).torque(250f).boost(9.5f).accel(255).build());

        final var engine = map(summarize(1, rows), "engine");

        assertThat(engine.get("maxRpm")).isEqualTo(8000.0);
        assertThat(engine.get("peakPowerHp")).isEqualTo(268.2);
        assertThat(engine.get("peakPowerRpm")).isEqualTo(7800.0);
        assertThat(engine.get("peakTorqueNm")).isEqualTo(300.0);
        assertThat(engine.get("peakTorqueRpm")).isEqualTo(6000.0);
        assertThat(engine.get("boostMaxPsi")).isEqualTo(9.5);
        final var gear3 = map(map(engine, "gears"), "3");
        assertThat(gear3.get("timePct")).isEqualTo(100.0);
        assertThat(gear3.get("limiterWithThrottlePct")).isEqualTo(50.0);
    }

    @Test
    void calculate_engine_ignoresNeutralAndReverseGears_theGameReportsNeutralAs11() {
        final var rows = List.of(
                sample().gear(3).rpm(6000f).build(),
                sample().gear(3).rpm(6200f).build(),
                sample().gear(11).rpm(6500f).accel(0).build(),
                sample().gear(0).rpm(2000f).build());

        final var gears = map(map(summarize(1, rows), "engine"), "gears");

        assertThat(gears).containsOnlyKeys("3");
        assertThat(map(gears, "3").get("timePct")).isEqualTo(100.0);
    }

    @Test
    void calculate_traction_ignoresSamplesInNeutralOrReverse() {
        final var rows = List.of(
                sample().gear(11).accel(255).slipRatio(0f, 0f, 3f, 3f).build(),
                sample().gear(0).accel(255).slipRatio(0f, 0f, 3f, 3f).build(),
                sample().gear(2).accel(255).slipRatio(0f, 0f, 0f, 0f).build());

        final var traction = map(summarize(1, rows), "traction");

        assertThat(traction.get("samples")).isEqualTo(1);
        assertThat(traction.get("drivenWheelSpinPct")).isEqualTo(0.0);
        assertThat(map(traction, "spinPctByGear")).containsOnlyKeys("2");
    }

    @Test
    void calculate_cornerBalance_classifiesEntryUndersteerAndExitOversteer() {
        final var rows = List.of(
                sample().steer(50).brake(100).slipAngle(0.8f, 0.8f, 0.2f, 0.2f).build(),
                sample().steer(-50).accel(200).slipAngle(0.1f, 0.1f, 0.7f, 0.7f).build());

        final var balance = map(summarize(1, rows), "cornerBalance");

        final var entry = map(balance, "entry");
        assertThat(entry.get("samples")).isEqualTo(1);
        assertThat(entry.get("understeerPct")).isEqualTo(100.0);
        assertThat(entry.get("oversteerPct")).isEqualTo(0.0);
        assertThat(entry.get("frontSlipAngleMean")).isEqualTo(0.8);
        assertThat(entry.get("rearSlipAngleMean")).isEqualTo(0.2);
        final var exit = map(balance, "exit");
        assertThat(exit.get("oversteerPct")).isEqualTo(100.0);
        assertThat(exit.get("understeerPct")).isEqualTo(0.0);
        assertThat(map(balance, "mid")).containsOnly(Map.entry("samples", 0));
    }

    @Test
    void calculate_cornerBalance_ignoresStraightsAndSlowSamples() {
        final var rows = List.of(
                sample().steer(10).brake(100).slipAngle(0.9f, 0.9f, 0.1f, 0.1f).build(),
                sample().steer(50).speed(3f).brake(100).slipAngle(0.9f, 0.9f, 0.1f, 0.1f).build());

        final var balance = map(summarize(1, rows), "cornerBalance");

        assertThat(map(balance, "entry")).containsOnly(Map.entry("samples", 0));
    }

    @Test
    void calculate_cornerBalance_differenceWithinThresholdIsNeutral() {
        final var rows = List.of(sample().steer(50).brake(100).slipAngle(0.5f, 0.5f, 0.4f, 0.4f).build());

        final var entry = map(map(summarize(1, rows), "cornerBalance"), "entry");

        assertThat(entry.get("understeerPct")).isEqualTo(0.0);
        assertThat(entry.get("oversteerPct")).isEqualTo(0.0);
    }

    @Test
    void calculate_braking_countsFrontAndRearLockupsOnlyUnderHardBraking() {
        final var rows = List.of(
                sample().brake(200).slipRatio(2.5f, 0f, 0f, 0f).build(),
                sample().brake(200).slipRatio(0f, 0f, 0f, -3f).build(),
                sample().brake(200).build(),
                sample().brake(100).slipRatio(3f, 3f, 3f, 3f).build());

        final var braking = map(summarize(1, rows), "braking");

        assertThat(braking.get("samples")).isEqualTo(3);
        assertThat(braking.get("frontLockPct")).isEqualTo(33.3);
        assertThat(braking.get("rearLockPct")).isEqualTo(33.3);
    }

    /**
     * Dados reais: com limiar 1.0 a dianteira "travava" em 13–62% das frenagens fortes de qualquer carro, porque apertar todo
     * o gatilho já passa de 1.0. Só slip ratio acima de 2.0 conta como travamento (a roda deslizando bem mais que girando).
     */
    @Test
    void calculate_braking_aHardPedalPressWithModerateSlip_isNotALockup() {
        final var rows = List.of(
                sample().brake(255).slipRatio(1.2f, 1.2f, 1.9f, 1.9f).build(),
                sample().brake(255).slipRatio(2.0f, 2.0f, 2.0f, 2.0f).build(),     // exatamente 2.0 ainda não é travamento
                sample().brake(255).slipRatio(2.01f, 0f, 2.01f, 0f).build());

        final var braking = map(summarize(1, rows), "braking");

        assertThat(braking.get("samples")).isEqualTo(3);
        assertThat(braking.get("frontLockPct")).isEqualTo(33.3);
        assertThat(braking.get("rearLockPct")).isEqualTo(33.3);
    }

    @ParameterizedTest(name = "drivetrain {0} -> wheelspin {1}%")
    @CsvSource({"0,50.0", "1,50.0", "2,100.0"})
    void calculate_traction_countsOnlyDrivenWheels(final int drivetrain, final double expectedSpinPct) {
        final var rows = List.of(
                sample().accel(255).gear(2).slipRatio(1.5f, 0f, 0f, 0f).build(),
                sample().accel(255).gear(2).slipRatio(0f, 0f, 1.5f, 0f).build());

        final var traction = map(summarize(drivetrain, rows), "traction");

        assertThat(traction.get("samples")).isEqualTo(2);
        assertThat(traction.get("drivenWheelSpinPct")).isEqualTo(expectedSpinPct);
        assertThat(map(traction, "spinPctByGear").get("2")).isEqualTo(expectedSpinPct);
    }

    @Test
    void calculate_traction_ignoresSamplesWhileBrakingOrWithLowThrottle() {
        final var rows = List.of(
                sample().accel(255).brake(50).slipRatio(0f, 0f, 3f, 3f).build(),
                sample().accel(100).slipRatio(0f, 0f, 3f, 3f).build());

        assertThat(map(summarize(1, rows), "traction").get("samples")).isEqualTo(0);
    }

    @Test
    void calculate_laps_listsTimesAndPicksBest() {
        final var out = calculator.calculate(session(1), List.of(sample().build()),
                List.of(new LapRecord(1, 62.5f), new LapRecord(2, 61.25f)));

        final var laps = map(out, "laps");
        assertThat(laps.get("bestS")).isEqualTo(61.25);
        assertThat((List<?>) laps.get("times")).hasSize(2);
    }

    @Test
    void calculate_noLaps_bestIsNull() {
        assertThat(map(summarize(1, List.of(sample().build())), "laps").get("bestS")).isNull();
    }

    @Test
    void calculate_rumbleStrip_reportsPercentageOfSamples() {
        final var rows = List.of(sample().onRumble(true).build(), sample().build(), sample().build(), sample().build());

        assertThat(summarize(1, rows).get("onRumbleStripPct")).isEqualTo(25.0);
    }

    @Test
    void calculate_tires_usesMovingSamplesAndReportsFinalWearWhenPresent() {
        final var rows = List.of(
                sample().speed(1f).tireTemp(100f, 100f, 100f, 100f).build(),
                sample().tireTemp(200f, 200f, 200f, 200f).build(),
                sample().tireTemp(220f, 220f, 220f, 220f).tireWear(0.1234f, 0.2f, 0.3f, 0.4f).build());

        final var fl = map(map(summarize(1, rows), "tires"), "FL");

        assertThat(fl.get("tempMeanF")).isEqualTo(210.0);
        assertThat(fl.get("tempP95F")).isEqualTo(220.0);
        assertThat(fl.get("wearFinal")).isEqualTo(0.123);
    }

    @Test
    void calculate_tires_withoutWearData_omitsWearFinal() {
        final var fl = map(map(summarize(1, List.of(sample().build())), "tires"), "FL");

        assertThat(fl).doesNotContainKey("wearFinal");
    }

    @Test
    void calculate_tires_whenNeverMoving_fallsBackToAllSamples() {
        final var rows = List.of(sample().speed(0f).tireTemp(90f, 90f, 90f, 90f).build());

        assertThat(map(map(summarize(1, rows), "tires"), "FL").get("tempMeanF")).isEqualTo(90.0);
    }
}
