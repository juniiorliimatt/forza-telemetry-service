package br.com.forza.tuning;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.forza.models.dto.TuningRecommendationDTO.TuningGuideDTO;
import br.com.forza.models.dto.TuningRecommendationDTO.TuningSuggestionDTO;
import br.com.forza.tuning.SetupCatalog.Range;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SetupTargetsTest {

    private static final Map<String, Range> RANGES = Map.of("spring_front", new Range(20, 220), "spring_rear", new Range(20, 220),
            "downforce_front", new Range(20, 220), "downforce_rear", new Range(20, 220));

    private static TuningSuggestionDTO suggestion(final int priority, final String guide, final String parameter, final String axle, final String direction,
                                                  final double amount, final String unit) {
        return new TuningSuggestionDTO(priority, priority <= 3, guide, parameter, axle, direction, "porque", "evidência", amount, unit, "SMALL");
    }

    private static TuningSuggestionDTO noStep(final int priority, final String guide, final String parameter, final String axle, final String direction) {
        return new TuningSuggestionDTO(priority, true, guide, parameter, axle, direction, "porque", "evidência", null, null, null);
    }

    private static TuningAdvisor.Advice advice(final TuningSuggestionDTO... suggestions) {
        final List<TuningGuideDTO> guides = List.of(new TuningGuideDTO("todas", "Todas", "ADJUST", "x", List.of(), List.of(suggestions)));
        return new TuningAdvisor.Advice(guides, java.util.Arrays.stream(suggestions).filter(TuningSuggestionDTO::thisCycle).toList());
    }

    private static Map<String, Double> values(final Object... pairs) {
        final Map<String, Double> map = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        return map;
    }

    @Test
    void targetIsCurrentMinusTheStepWhenDecreasing() {
        final var result = SetupTargets.apply(advice(suggestion(1, "pneus", "Pressão dos pneus dianteiros", "FRONT", "DECREASE", 0.1, "bar")),
                values("tire_pressure_front", 1.8), RANGES);

        final var s = result.thisCycle().get(0);
        assertThat(s.setupKey()).isEqualTo("tire_pressure_front");
        assertThat(s.current()).isEqualTo(1.8);
        assertThat(s.target()).isEqualTo(1.7);
        assertThat(s.atLimit()).isFalse();
    }

    @Test
    void targetIsCurrentPlusTheStepWhenIncreasing() {
        final var result = SetupTargets.apply(advice(suggestion(1, "barras", "Barra traseira (anti-rolagem)", "REAR", "INCREASE", 4.0, "pontos")),
                values("arb_rear", 30), RANGES);

        assertThat(result.thisCycle().get(0).target()).isEqualTo(34.0);
    }

    @Test
    void springStepIsPercentagePointsOfTheSliderTravel() {
        // curso 20–220 = 200; 5 pontos percentuais = 10 unidades
        final var result = SetupTargets.apply(advice(suggestion(1, "molas", "Mola traseira", "REAR", "DECREASE", 5.0, "pontos percentuais do curso do slider")),
                values("spring_rear", 100), RANGES);

        assertThat(result.thisCycle().get(0).target()).isEqualTo(90.0);
    }

    @Test
    void clampsToTheLimitButStillMovesWhenThereIsRoom() {
        final var result = SetupTargets.apply(advice(suggestion(1, "pneus", "Pressão dos pneus traseiros", "REAR", "INCREASE", 0.1, "bar")),
                values("tire_pressure_rear", 3.75), RANGES);

        final var s = result.thisCycle().get(0);
        assertThat(s.target()).isEqualTo(3.8);
        assertThat(s.atLimit()).isFalse();
    }

    @Test
    void flagsAtLimitAndPromotesTheNextSuggestionIntoTheCycle() {
        final var stuck = suggestion(1, "pneus", "Pressão dos pneus dianteiros", "FRONT", "DECREASE", 0.1, "bar");
        final var next = suggestion(4, "barras", "Barra traseira (anti-rolagem)", "REAR", "DECREASE", 2.0, "pontos");
        final var result = SetupTargets.apply(advice(stuck, next), values("tire_pressure_front", 1.0, "arb_rear", 20), RANGES);

        final var all = result.guides().get(0).suggestions();
        assertThat(all).filteredOn(s -> s.parameter().startsWith("Pressão")).singleElement().satisfies(s -> {
            assertThat(s.atLimit()).isTrue();
            assertThat(s.target()).isEqualTo(1.0);
            assertThat(s.thisCycle()).isFalse();
        });
        assertThat(result.thisCycle()).extracting(TuningSuggestionDTO::parameter).containsExactly("Barra traseira (anti-rolagem)");
        assertThat(result.thisCycle().get(0).priority()).isEqualTo(1);
    }

    @Test
    void suggestionWithoutASetupFieldKeepsOnlyTheStep() {
        final var result = SetupTargets.apply(advice(suggestion(1, "molas", "Altura do solo traseira", "REAR", "INCREASE", 0.5, "cm")), values(), RANGES);

        final var s = result.thisCycle().get(0);
        assertThat(s.setupKey()).isNull();
        assertThat(s.target()).isNull();
        assertThat(s.atLimit()).isFalse();
        assertThat(s.amount()).isEqualTo(0.5);
    }

    @Test
    void suggestionWithoutAStepOrWithoutACurrentValueIsLeftAlone() {
        final var result = SetupTargets.apply(advice(noStep(1, "pneus", "Pressão dos pneus dianteiros", "FRONT", "DECREASE")),
                values("tire_pressure_front", 1.8), RANGES);

        assertThat(result.thisCycle().get(0).target()).isNull();
    }
}
