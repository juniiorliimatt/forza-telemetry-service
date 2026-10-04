package br.com.forza.models.dto;

import br.com.forza.models.entities.SessionMeta;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadados de uma sessão. {@code active} = ainda recebendo pacotes (sem {@code endedAt});
 * {@code drivetrain} = FWD/RWD/AWD; {@code carClass} é o índice numérico do jogo; {@code samplesPurged} = as
 * amostras brutas foram apagadas ao reiniciar a coleta (resumo e voltas continuam, a telemetria some).
 */
public record SessionDTO(UUID id,
                         String gameFormat,
                         int carOrdinal,
                         String carName,
                         int carClass,
                         int performanceIndex,
                         String drivetrain,
                         int cylinders,
                         Integer trackOrdinal,
                         Instant startedAt,
                         Instant endedAt,
                         int sampleCount,
                         boolean active,
                         boolean samplesPurged) {

    public static SessionDTO from(final SessionMeta meta) {
        return from(meta, null);
    }

    /** {@code carName} vem do catálogo ({@code CarCatalog}); nulo quando o ordinal não é conhecido. */
    public static SessionDTO from(final SessionMeta meta, final String carName) {
        return new SessionDTO(
                meta.id(),
                meta.gameFormat(),
                meta.carOrdinal(),
                carName,
                meta.carClass(),
                meta.performanceIndex(),
                drivetrainName(meta.drivetrain()),
                meta.cylinders(),
                meta.trackOrdinal(),
                meta.startedAt(),
                meta.endedAt(),
                meta.sampleCount(),
                meta.endedAt() == null,
                meta.samplesPurgedAt() != null);
    }

    private static String drivetrainName(final int code) {
        return switch (code) {
            case 0 -> "FWD";
            case 1 -> "RWD";
            case 2 -> "AWD";
            default -> "UNKNOWN";
        };
    }
}
