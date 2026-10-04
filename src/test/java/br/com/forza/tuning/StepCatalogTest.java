package br.com.forza.tuning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * O Data Out não traz o valor atual do setup nem o curso dos sliders (nem o forzahorizonhub lista valores stock por carro),
 * então o app dá o <b>tamanho do passo</b> do ajuste — não o valor final — na unidade que o jogo mostra.
 */
class StepCatalogTest {

    @ParameterizedTest(name = "{0} (severidade {1}) → {2} {3}")
    @CsvSource(delimiter = '|', value = {
            "Pressão dos pneus dianteiros|1.0|0.1|bar",
            "Pressão dos pneus traseiros|1.0|0.1|bar",
            "Cambagem dianteira (mais negativa)|1.0|0.2|°",
            "Convergência dianteira (mais aberta / toe-out)|1.0|0.1|°",
            "Barra dianteira (anti-rolagem)|1.0|2.0|pontos",
            "Barra traseira (anti-rolagem)|1.0|2.0|pontos",
            "Mola dianteira|1.0|5.0|pontos percentuais do curso do slider",
            "Mola traseira|1.0|5.0|pontos percentuais do curso do slider",
            "Altura do solo dianteira|1.0|0.5|cm",
            "Rigidez de compressão traseira|1.0|1.0|pontos",
            "Pressão de freio|1.0|5.0|pontos percentuais",
            "Equilíbrio de freio|1.0|1.0|pontos percentuais",
            "Diferencial traseiro — aceleração|1.0|5.0|pontos percentuais",
            "Diferencial dianteiro — desaceleração|1.0|5.0|pontos percentuais",
            "Diferencial central (mais para a traseira)|1.0|5.0|pontos percentuais",
            "Relação final (transmissão final)|1.0|0.1|na relação",
            "Relação da 4ª marcha|1.0|0.1|na relação",
            "Relações da 1ª à 3ª marcha (mais longas)|1.0|0.1|na relação"})
    void baseStepPerParameter_isInTheUnitTheGameShows(final String parameter, final double severity, final double amount, final String unit) {
        final var step = StepCatalog.stepFor(parameter, severity).orElseThrow();

        assertThat(step.amount()).isEqualTo(amount);
        assertThat(step.unit()).isEqualTo(unit);
        assertThat(step.magnitude()).isEqualTo("SMALL");
    }

    @Test
    void theStepGrowsWithTheSeverity_smallMediumLarge() {
        final var small = StepCatalog.stepFor("Barra dianteira (anti-rolagem)", 1.2).orElseThrow();
        final var medium = StepCatalog.stepFor("Barra dianteira (anti-rolagem)", 2.0).orElseThrow();
        final var large = StepCatalog.stepFor("Barra dianteira (anti-rolagem)", 3.4).orElseThrow();

        assertThat(small.amount()).isEqualTo(2.0);
        assertThat(small.magnitude()).isEqualTo("SMALL");
        assertThat(medium.amount()).isEqualTo(4.0);
        assertThat(medium.magnitude()).isEqualTo("MEDIUM");
        assertThat(large.amount()).isEqualTo(6.0);
        assertThat(large.magnitude()).isEqualTo("LARGE");
    }

    @Test
    void severityBoundaries_areHalfOpen() {
        assertThat(StepCatalog.stepFor("Pressão de freio", 1.49).orElseThrow().magnitude()).isEqualTo("SMALL");
        assertThat(StepCatalog.stepFor("Pressão de freio", 1.5).orElseThrow().magnitude()).isEqualTo("MEDIUM");
        assertThat(StepCatalog.stepFor("Pressão de freio", 2.49).orElseThrow().magnitude()).isEqualTo("MEDIUM");
        assertThat(StepCatalog.stepFor("Pressão de freio", 2.5).orElseThrow().magnitude()).isEqualTo("LARGE");
    }

    @Test
    void aSeverityBelowOne_stillGetsTheSmallStep() {
        assertThat(StepCatalog.stepFor("Pressão dos pneus dianteiros", 0.2).orElseThrow().magnitude()).isEqualTo("SMALL");
    }

    @Test
    void decimalsAreRoundedToTwoPlaces() {
        assertThat(StepCatalog.stepFor("Relação final (transmissão final)", 3.0).orElseThrow().amount()).isEqualTo(0.3);
        assertThat(StepCatalog.stepFor("Pressão dos pneus traseiros", 2.0).orElseThrow().amount()).isEqualTo(0.2);
    }

    @Test
    void anUnknownParameter_hasNoStep_insteadOfAnInventedNumber() {
        assertThat(StepCatalog.stepFor("Parâmetro que não existe", 1.0)).isEmpty();
    }
}
