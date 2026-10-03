package br.com.forza.telemetry.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.forza.support.PacketBuilder;
import br.com.forza.telemetry.packet.PacketFormat;
import br.com.forza.telemetry.packet.PacketParser;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LiveSnapshotTest {

    private final LiveSnapshot snapshot = new LiveSnapshot();

    private static br.com.forza.telemetry.packet.TelemetryPacket packet() {
        return PacketParser.parse(PacketBuilder.racing(PacketFormat.HORIZON).build()).orElseThrow();
    }

    @Test
    void current_beforeAnyPacket_isEmpty() {
        assertThat(snapshot.current(Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    void current_afterUpdate_returnsLatestPacket() {
        snapshot.update(packet());

        assertThat(snapshot.current(Duration.ofSeconds(5))).isPresent();
    }

    @Test
    void current_keepsOnlyTheMostRecentPacket() {
        snapshot.update(PacketParser.parse(PacketBuilder.racing(PacketFormat.HORIZON).car(1, 1, 1, 1, 1).build()).orElseThrow());
        snapshot.update(PacketParser.parse(PacketBuilder.racing(PacketFormat.HORIZON).car(2, 1, 1, 1, 1).build()).orElseThrow());

        assertThat(snapshot.current(Duration.ofSeconds(5)).orElseThrow().packet().carOrdinal()).isEqualTo(2);
    }

    @Test
    void current_olderThanStaleWindow_isEmpty() throws InterruptedException {
        snapshot.update(packet());
        Thread.sleep(40);

        assertThat(snapshot.current(Duration.ofMillis(10))).isEmpty();
    }
}
