package br.com.forza.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Quando há dados suficientes pra recomendar tuning (prefixo {@code tuning}). Contagem de sessões não basta:
 * uma sessão pode ter segundos. Exige também volume mínimo de amostras (30000 = 10 sessões cheias de 3000, a ~20 Hz
 * ≈ 25 min de pilotagem gravada) e olha só as {@code maxSessions} mais recentes, pra setups antigos saírem da janela.
 */
@ConfigurationProperties(prefix = "tuning")
public record TuningProperties(int minSessions, long minSamples, int maxSessions) {
}
