package br.com.forza.telemetry;

/**
 * Classes de desempenho do Forza Horizon 6 por Índice de Performance (PI), conforme forzahorizonhub.com:
 * D 100–400, C 401–500, B 501–600, A 601–700, S1 701–800, S2 801–900, R 901–998. Cada upgrade que muda a classe é
 * outra "build" do carro: o tuning separa a coleta por carro e classe, para não misturar setups de builds diferentes.
 * PI fora da faixa (0, 999+) cai na classe mais próxima.
 */
public enum PerformanceClass {
    D(0, 400),
    C(401, 500),
    B(501, 600),
    A(601, 700),
    S1(701, 800),
    S2(801, 900),
    R(901, Integer.MAX_VALUE);

    private final int minPi;
    private final int maxPi;

    PerformanceClass(final int minPi, final int maxPi) {
        this.minPi = minPi;
        this.maxPi = maxPi;
    }

    public int minPi() {
        return minPi;
    }

    public int maxPi() {
        return maxPi;
    }

    public static PerformanceClass of(final int performanceIndex) {
        for (final PerformanceClass c : values()) {
            if (performanceIndex <= c.maxPi) {
                return c;
            }
        }
        return R;
    }
}
