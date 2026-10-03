package br.com.forza.models.dto;

import java.time.Instant;

/**
 * Um carro, em uma classe de PI (cada classe é uma build), com sessões coletadas e o quanto falta pra gerar
 * recomendação. {@code performanceClass}: D, C, B, A, S1, S2 ou R (faixas do FH6); {@code carClass} é o valor cru do pacote.
 */
public record TuningCarDTO(int carOrdinal,
                           String carName,
                           int carClass,
                           int performanceIndex,
                           String performanceClass,
                           String drivetrain,
                           int sessions,
                           long samples,
                           int requiredSessions,
                           boolean ready,
                           Instant lastSessionAt) {
}
