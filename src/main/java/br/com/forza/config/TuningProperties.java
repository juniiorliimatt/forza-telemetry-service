package br.com.forza.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Quando há dados suficientes pra recomendar tuning (prefixo {@code tuning}), em dois níveis. Contagem de sessões não
 * basta (uma sessão pode ter poucas amostras), então cada nível exige sessões <b>e</b> amostras (tempo não entra na regra):
 * <ul>
 *   <li>inicial ({@code minSessions}/{@code minSamples}): libera a recomendação — 5 sessões e 20000 amostras (~17 min a 20 Hz);</li>
 *   <li>alta confiança ({@code highMinSessions}/{@code highMinSamples}): ajuste fino — 10 sessões e 50000 amostras (~42 min).</li>
 * </ul>
 * Só as {@code maxSessions} mais recentes entram, pra setups antigos saírem da janela.
 * {@code keepSampleSessions}: ao reiniciar a coleta, quantas sessões mais recentes do carro/classe mantêm as amostras brutas.
 */
@ConfigurationProperties(prefix = "tuning")
public record TuningProperties(int minSessions, long minSamples, int highMinSessions, long highMinSamples, int maxSessions, int sessionSamples,
                               int keepSampleSessions) {
}
