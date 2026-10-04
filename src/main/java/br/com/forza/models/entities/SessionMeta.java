package br.com.forza.models.entities;

import java.time.Instant;
import java.util.UUID;

/**
 * Linha de {@code forza.sessions}. {@code summaryJson} é o resumo de tuning serializado
 * (jsonb), {@code null} enquanto a sessão está ativa ou se o cálculo falhou. {@code samplesPurgedAt} é o
 * instante em que as amostras brutas foram apagadas (reinício da coleta); {@code null} = ainda guardadas.
 */
public record SessionMeta(UUID id,
                          String gameFormat,
                          int carOrdinal,
                          int carClass,
                          int performanceIndex,
                          int drivetrain,
                          int cylinders,
                          float engineMaxRpm,
                          float engineIdleRpm,
                          Integer trackOrdinal,
                          Instant startedAt,
                          Instant endedAt,
                          int sampleCount,
                          String summaryJson,
                          Instant samplesPurgedAt) {

    /** Sessão com as amostras ainda guardadas (o caso comum, inclusive sessões recém-abertas). */
    public SessionMeta(final UUID id, final String gameFormat, final int carOrdinal, final int carClass, final int performanceIndex,
                       final int drivetrain, final int cylinders, final float engineMaxRpm, final float engineIdleRpm,
                       final Integer trackOrdinal, final Instant startedAt, final Instant endedAt, final int sampleCount,
                       final String summaryJson) {
        this(id, gameFormat, carOrdinal, carClass, performanceIndex, drivetrain, cylinders, engineMaxRpm, engineIdleRpm,
                trackOrdinal, startedAt, endedAt, sampleCount, summaryJson, null);
    }
}
