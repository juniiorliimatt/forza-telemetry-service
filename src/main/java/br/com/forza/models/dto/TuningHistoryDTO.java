package br.com.forza.models.dto;

import java.time.Instant;
import java.util.UUID;

/** Um tuning salvo: a recomendação exatamente como estava quando foi gravada (todas as guias e os ajustes do ciclo). */
public record TuningHistoryDTO(UUID id, Instant savedAt, TuningRecommendationDTO recommendation) {
}
