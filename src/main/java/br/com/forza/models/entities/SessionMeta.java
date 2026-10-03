package br.com.forza.models.entities;

import java.time.Instant;
import java.util.UUID;

/**
 * Linha de {@code forza.sessions}. {@code summaryJson} é o resumo de tuning serializado
 * (jsonb), {@code null} enquanto a sessão está ativa ou se o cálculo falhou.
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
                          String summaryJson) {
}
