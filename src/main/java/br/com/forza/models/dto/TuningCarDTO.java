package br.com.forza.models.dto;

import java.time.Instant;

/** Um carro com sessões coletadas e o quanto falta pra gerar recomendação. */
public record TuningCarDTO(int carOrdinal,
                           String carName,
                           int carClass,
                           int performanceIndex,
                           String drivetrain,
                           int sessions,
                           long samples,
                           int requiredSessions,
                           boolean ready,
                           Instant lastSessionAt) {
}
