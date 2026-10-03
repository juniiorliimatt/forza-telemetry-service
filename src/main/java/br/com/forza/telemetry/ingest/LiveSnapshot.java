package br.com.forza.telemetry.ingest;

import br.com.forza.telemetry.packet.TelemetryPacket;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Último pacote recebido, em memória — alimenta {@code GET /api/v1/live/snapshot}. */
@Component
public class LiveSnapshot {

    public record Reading(TelemetryPacket packet, Instant receivedAt) {
    }

    private volatile Reading latest;

    public void update(final TelemetryPacket packet) {
        latest = new Reading(packet, Instant.now());
    }

    /** Leitura mais recente, ou vazio se nada chegou dentro de {@code staleAfter}. */
    public Optional<Reading> current(final Duration staleAfter) {
        final Reading reading = latest;
        if (reading == null || reading.receivedAt().isBefore(Instant.now().minus(staleAfter))) {
            return Optional.empty();
        }
        return Optional.of(reading);
    }
}
