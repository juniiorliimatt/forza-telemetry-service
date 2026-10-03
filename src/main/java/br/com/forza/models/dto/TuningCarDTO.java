package br.com.forza.models.dto;

import java.time.Instant;

/**
 * Um carro, em uma classe de PI (cada classe é uma build), com sessões coletadas e o quanto falta pra gerar
 * recomendação. {@code performanceClass}: D, C, B, A, S1, S2 ou R (faixas do FH6); {@code carClass} é o valor cru do pacote.
 *
 * @param activeSession sessão em andamento nessa build (ainda não conta no progresso), ou nulo
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
                           long requiredSamples,
                           boolean ready,
                           Instant lastSessionAt,
                           ActiveSessionDTO activeSession) {

    /** Sessão sendo gravada agora: amostras já gravadas e o tamanho da sessão cheia (ao chegar nele, ela fecha e passa a contar). */
    public record ActiveSessionDTO(int samples, int targetSamples, Instant startedAt) {
    }
}
