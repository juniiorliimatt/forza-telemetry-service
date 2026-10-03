package br.com.forza.models.dto;

import br.com.forza.telemetry.ingest.LiveSnapshot;
import br.com.forza.telemetry.packet.TelemetryPacket;
import java.time.Instant;

/**
 * Último pacote recebido. Campos de Dash ({@code speedKmh}, {@code gear}, inputs, pneus,
 * voltas) vêm nulos se o jogo estiver enviando o formato Sled.
 */
public record LiveSnapshotDTO(Instant receivedAt,
                              String gameFormat,
                              boolean raceOn,
                              int carOrdinal,
                              String carName,
                              int performanceIndex,
                              float rpm,
                              float engineMaxRpm,
                              Float speedKmh,
                              Integer gear,
                              Integer accel,
                              Integer brake,
                              Integer steer,
                              float[] suspension,
                              float[] slipAngle,
                              float[] combinedSlip,
                              float[] tireTempF,
                              Integer lapNumber,
                              Float currentLapS,
                              Float lastLapS,
                              Float bestLapS) {

    public static LiveSnapshotDTO from(final LiveSnapshot.Reading reading) {
        return from(reading, null);
    }

    public static LiveSnapshotDTO from(final LiveSnapshot.Reading reading, final String carName) {
        final TelemetryPacket p = reading.packet();
        final TelemetryPacket.Dash d = p.dash();
        return new LiveSnapshotDTO(
                reading.receivedAt(),
                p.format().label(),
                p.raceOn(),
                p.carOrdinal(),
                carName,
                p.performanceIndex(),
                p.currentRpm(),
                p.engineMaxRpm(),
                d == null ? null : d.speed() * 3.6f,
                d == null ? null : d.gear(),
                d == null ? null : d.accel(),
                d == null ? null : d.brake(),
                d == null ? null : d.steer(),
                p.suspension(),
                p.slipAngle(),
                p.combinedSlip(),
                d == null ? null : d.tireTemp(),
                d == null ? null : d.lapNumber(),
                d == null ? null : d.currentLap(),
                d == null ? null : d.lastLap(),
                d == null ? null : d.bestLap());
    }
}
