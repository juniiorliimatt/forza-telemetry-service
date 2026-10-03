package br.com.forza.telemetry.packet;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.forza.support.PacketBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PacketParserTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 10, 231, 233, 500})
    void parse_unknownSize_returnsEmpty(final int size) {
        assertThat(PacketParser.parse(new byte[size])).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(PacketFormat.class)
    void parse_detectsFormatBySize(final PacketFormat format) {
        final var parsed = PacketParser.parse(PacketBuilder.of(format).build());

        assertThat(parsed).isPresent();
        assertThat(parsed.get().format()).isEqualTo(format);
    }

    @Test
    void parse_sled_hasNoDashBlock() {
        final var packet = PacketParser.parse(PacketBuilder.racing(PacketFormat.SLED).build()).orElseThrow();

        assertThat(packet.dash()).isNull();
        assertThat(packet.raceOn()).isTrue();
        assertThat(packet.carOrdinal()).isEqualTo(1234);
    }

    @Test
    void parse_horizon_readsCommonBlockFields() {
        final var bytes = PacketBuilder.racing(PacketFormat.HORIZON)
                .timestampMs(42_000)
                .acceleration(1.5f, -0.5f, 9.8f)
                .suspension(0.1f, 0.2f, 0.3f, 0.4f)
                .slipRatio(0.5f, 0.6f, 0.7f, 0.8f)
                .slipAngle(-0.1f, -0.2f, 0.3f, 0.4f)
                .combinedSlip(1f, 2f, 3f, 4f)
                .build();

        final var p = PacketParser.parse(bytes).orElseThrow();

        assertThat(p.raceOn()).isTrue();
        assertThat(p.timestampMs()).isEqualTo(42_000L);
        assertThat(p.engineMaxRpm()).isEqualTo(8000f);
        assertThat(p.engineIdleRpm()).isEqualTo(1000f);
        assertThat(p.currentRpm()).isEqualTo(4000f);
        assertThat(p.accelerationX()).isEqualTo(1.5f);
        assertThat(p.accelerationY()).isEqualTo(-0.5f);
        assertThat(p.accelerationZ()).isEqualTo(9.8f);
        assertThat(p.suspension()).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(p.slipRatio()).containsExactly(0.5f, 0.6f, 0.7f, 0.8f);
        assertThat(p.slipAngle()).containsExactly(-0.1f, -0.2f, 0.3f, 0.4f);
        assertThat(p.combinedSlip()).containsExactly(1f, 2f, 3f, 4f);
        assertThat(p.carOrdinal()).isEqualTo(1234);
        assertThat(p.carClass()).isEqualTo(5);
        assertThat(p.performanceIndex()).isEqualTo(800);
        assertThat(p.drivetrain()).isEqualTo(1);
        assertThat(p.cylinders()).isEqualTo(8);
    }

    @Test
    void parse_horizon_readsDashFieldsFromOffset244() {
        final var bytes = PacketBuilder.racing(PacketFormat.HORIZON)
                .position(10f, 20f, 30f)
                .speed(55.5f)
                .power(300_000f)
                .torque(450f)
                .tireTemp(180f, 181f, 190f, 191f)
                .boost(12.5f)
                .fuel(0.75f)
                .lapTimes(61.2f, 62.3f, 15.4f)
                .lapNumber(3)
                .racePosition(2)
                .inputs(200, 10, 0, 0, 4, -80)
                .build();

        final var dash = PacketParser.parse(bytes).orElseThrow().dash();

        assertThat(dash.positionX()).isEqualTo(10f);
        assertThat(dash.positionY()).isEqualTo(20f);
        assertThat(dash.positionZ()).isEqualTo(30f);
        assertThat(dash.speed()).isEqualTo(55.5f);
        assertThat(dash.power()).isEqualTo(300_000f);
        assertThat(dash.torque()).isEqualTo(450f);
        assertThat(dash.tireTemp()).containsExactly(180f, 181f, 190f, 191f);
        assertThat(dash.boost()).isEqualTo(12.5f);
        assertThat(dash.fuel()).isEqualTo(0.75f);
        assertThat(dash.bestLap()).isEqualTo(61.2f);
        assertThat(dash.lastLap()).isEqualTo(62.3f);
        assertThat(dash.currentLap()).isEqualTo(15.4f);
        assertThat(dash.lapNumber()).isEqualTo(3);
        assertThat(dash.racePosition()).isEqualTo(2);
        assertThat(dash.accel()).isEqualTo(200);
        assertThat(dash.brake()).isEqualTo(10);
        assertThat(dash.gear()).isEqualTo(4);
        assertThat(dash.steer()).isEqualTo(-80);
        assertThat(dash.tireWear()).isNull();
        assertThat(dash.trackOrdinal()).isNull();
    }

    @Test
    void parse_forzaMotorsport_readsDashFromOffset232AndTireWearAndTrack() {
        final var bytes = PacketBuilder.racing(PacketFormat.FM_DASH)
                .speed(30f)
                .inputs(255, 0, 0, 0, 2, 127)
                .tireWear(0.1f, 0.2f, 0.3f, 0.4f)
                .trackOrdinal(77)
                .build();

        final var dash = PacketParser.parse(bytes).orElseThrow().dash();

        assertThat(dash.speed()).isEqualTo(30f);
        assertThat(dash.accel()).isEqualTo(255);
        assertThat(dash.steer()).isEqualTo(127);
        assertThat(dash.tireWear()).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(dash.trackOrdinal()).isEqualTo(77);
    }

    @Test
    void parse_forzaMotorsport7_hasDashButNoTireWear() {
        final var dash = PacketParser.parse(PacketBuilder.racing(PacketFormat.FM7_DASH).speed(10f).build())
                .orElseThrow().dash();

        assertThat(dash.speed()).isEqualTo(10f);
        assertThat(dash.tireWear()).isNull();
        assertThat(dash.trackOrdinal()).isNull();
    }

    @Test
    void parse_raceOffPacket_reportsRaceOnFalse() {
        final var packet = PacketParser.parse(PacketBuilder.of(PacketFormat.HORIZON).raceOn(false).build()).orElseThrow();

        assertThat(packet.raceOn()).isFalse();
    }

    @Test
    void parse_timestampAbove2Pow31_isUnsigned() {
        final var packet = PacketParser.parse(PacketBuilder.of(PacketFormat.HORIZON).timestampMs(0xFFFFFFFFL).build())
                .orElseThrow();

        assertThat(packet.timestampMs()).isEqualTo(4_294_967_295L);
    }

    @Test
    void parse_noWheelOnRumbleStrip_isFalse() {
        assertThat(PacketParser.parse(PacketBuilder.of(PacketFormat.HORIZON).build()).orElseThrow().onRumble()).isFalse();
    }

    @ParameterizedTest(name = "roda {0} na zebra")
    @ValueSource(ints = {0, 1, 2, 3})
    void parse_anySingleWheelOnRumbleStrip_setsOnRumble(final int wheel) {
        final var packet = PacketParser.parse(PacketBuilder.of(PacketFormat.HORIZON).rumble(wheel, true).build()).orElseThrow();

        assertThat(packet.onRumble()).isTrue();
    }

    @Test
    void parse_pedalsAbove127_areUnsigned() {
        final var dash = PacketParser.parse(PacketBuilder.racing(PacketFormat.HORIZON).inputs(200, 250, 255, 128, 1, 0).build())
                .orElseThrow().dash();

        assertThat(dash.accel()).isEqualTo(200);
        assertThat(dash.brake()).isEqualTo(250);
        assertThat(dash.clutch()).isEqualTo(255);
        assertThat(dash.handbrake()).isEqualTo(128);
    }
}
