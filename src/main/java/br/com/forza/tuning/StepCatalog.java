package br.com.forza.tuning;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Tamanho do passo de cada ajuste do tuning. O Data Out não traz o valor atual do setup nem o curso dos sliders (e o
 * forzahorizonhub confirma que não há valores stock por carro), então o app não diz o valor final: diz <b>quanto mexer
 * neste ciclo</b>, na unidade que o jogo mostra. O passo cresce com a severidade do sintoma (pequeno ×1, médio ×2,
 * grande ×3) e é pensado para um ciclo de teste de 2–3 voltas; valores que dependem do carro (molas) vão em % do curso do
 * slider. Escalas de referência (skill {@code forza-tuning-engineer}): barras 1–65, amortecimento ~1–20, pressão em bar.
 */
final class StepCatalog {

    /** Passo de um ajuste: {@code magnitude} é SMALL, MEDIUM ou LARGE. */
    record Step(double amount, String unit, String magnitude) {
    }

    private record Base(String keyword, double amount, String unit) {
    }

    /** Primeira palavra-chave (minúscula) contida no parâmetro vence — a ordem importa ("pressão de freio" antes de "pressão dos pneus"). */
    private static final List<Base> BASES = List.of(
            new Base("pressão de freio", 5.0, "pontos percentuais"),
            new Base("equilíbrio de freio", 1.0, "pontos percentuais"),
            new Base("pressão dos pneus", 0.1, "bar"),
            new Base("cambagem", 0.2, "°"),
            new Base("convergência", 0.1, "°"),
            new Base("barra", 2.0, "pontos"),
            new Base("mola", 5.0, "% do curso do slider"),
            new Base("altura do solo", 0.5, "cm"),
            new Base("rigidez de", 1.0, "pontos"),
            new Base("diferencial", 5.0, "pontos percentuais"),
            new Base("relação", 0.1, "na relação"),
            new Base("relações", 0.1, "na relação"));

    private static final double MEDIUM_FROM = 1.5;
    private static final double LARGE_FROM = 2.5;

    private StepCatalog() {
    }

    /** Vazio para parâmetro desconhecido: melhor sem número do que inventado. */
    static Optional<Step> stepFor(final String parameter, final double severity) {
        final String name = parameter.toLowerCase(Locale.ROOT);
        return BASES.stream().filter(b -> name.contains(b.keyword())).findFirst().map(b -> {
            final int factor = severity >= LARGE_FROM ? 3 : severity >= MEDIUM_FROM ? 2 : 1;
            final String magnitude = factor == 3 ? "LARGE" : factor == 2 ? "MEDIUM" : "SMALL";
            return new Step(Math.round(b.amount() * factor * 100.0) / 100.0, b.unit(), magnitude);
        });
    }
}
