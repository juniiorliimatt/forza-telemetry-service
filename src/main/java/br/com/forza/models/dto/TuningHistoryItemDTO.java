package br.com.forza.models.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Um tuning salvo no histórico (listagem). A foto é gravada quando a coleta do carro é reiniciada com dados
 * suficientes; {@code adjustments} é quantos ajustes ela indicava para aquele ciclo.
 */
public record TuningHistoryItemDTO(UUID id,
                                   int carOrdinal,
                                   String carName,
                                   int carClass,
                                   int performanceIndex,
                                   String drivetrain,
                                   Instant savedAt,
                                   Instant windowFrom,
                                   Instant windowTo,
                                   int sessions,
                                   long samples,
                                   int adjustments) {
}
