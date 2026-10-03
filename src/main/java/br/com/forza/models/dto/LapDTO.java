package br.com.forza.models.dto;

import br.com.forza.models.entities.LapRecord;

public record LapDTO(int lapNumber, float lapTimeS) {

    public static LapDTO from(final LapRecord lap) {
        return new LapDTO(lap.lapNumber(), lap.lapTimeS());
    }
}
