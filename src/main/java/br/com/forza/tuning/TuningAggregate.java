package br.com.forza.tuning;

import java.util.Map;

/**
 * Métricas de várias sessões do mesmo carro combinadas (média ponderada pela quantidade de amostras
 * de cada sessão). Rodas nas chaves FL, FR, RL, RR; temperaturas em °F.
 */
public record TuningAggregate(int sessions,
                              long samples,
                              Map<String, Double> bottomingPct,
                              Map<String, Double> toppingPct,
                              Map<String, Double> tireTempF,
                              Phase entry,
                              Phase mid,
                              Phase exit,
                              long brakingSamples,
                              double frontLockPct,
                              double rearLockPct,
                              long tractionSamples,
                              double spinPct,
                              Map<String, Double> spinPctByGear,
                              Map<String, Double> limiterPctByGear,
                              double topSpeedKmh,
                              double onRumbleStripPct) {

    /** Fase da curva (entrada/meio/saída): amostras e % de subesterço/sobresterço. */
    public record Phase(long samples, double understeerPct, double oversteerPct) {
    }
}
