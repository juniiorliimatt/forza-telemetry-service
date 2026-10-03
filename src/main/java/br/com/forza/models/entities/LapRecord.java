package br.com.forza.models.entities;

/** Linha de {@code forza.laps}: tempo de uma volta concluída (numeração do próprio jogo). */
public record LapRecord(int lapNumber, float lapTimeS) {
}
