package br.com.forza.tuning;

import static br.com.forza.tuning.TuningFixtures.AWD;
import static br.com.forza.tuning.TuningFixtures.FWD;
import static br.com.forza.tuning.TuningFixtures.RWD;
import static br.com.forza.tuning.TuningFixtures.healthy;
import static br.com.forza.tuning.TuningFixtures.with;
import static br.com.forza.tuning.TuningFixtures.wheels;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.forza.models.dto.TuningRecommendationDTO.TuningGuideDTO;
import br.com.forza.models.dto.TuningRecommendationDTO.TuningSuggestionDTO;
import br.com.forza.tuning.TuningAggregate.Phase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TuningAdvisorTest {

    private final TuningAdvisor advisor = new TuningAdvisor();

    private static TuningGuideDTO guide(final List<TuningGuideDTO> guides, final String id) {
        return guides.stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<TuningSuggestionDTO> all(final List<TuningGuideDTO> guides) {
        return guides.stream().flatMap(g -> g.suggestions().stream()).toList();
    }

    @Test
    void alwaysReturnsEveryTuningGuideOfTheGameInAFixedOrder() {
        final var guides = advisor.advise(healthy(), RWD).guides();

        assertThat(guides).extracting(TuningGuideDTO::id).containsExactly(
                "pneus", "cambio", "alinhamento", "barras", "molas", "amortecimento", "aerodinamica", "freios", "diferencial");
        assertThat(guides).extracting(TuningGuideDTO::title).containsExactly(
                "Pneus", "Câmbio", "Alinhamento", "Barras anti-rolagem", "Molas", "Amortecimento", "Aerodinâmica", "Freios", "Diferencial");
    }

    @Test
    void aHealthyCar_hasNothingToAdjust_butEveryGuideStillExplainsItself() {
        final var guides = advisor.advise(healthy(), RWD).guides();

        assertThat(all(guides)).isEmpty();
        assertThat(guides).allSatisfy(g -> {
            assertThat(g.status()).isIn("OK", "NO_SIGNAL");
            assertThat(g.summary()).isNotBlank();
        });
        assertThat(guide(guides, "pneus").status()).isEqualTo("OK");
        assertThat(guide(guides, "aerodinamica").status()).isEqualTo("NO_SIGNAL");
        assertThat(guide(guides, "aerodinamica").notes()).isNotEmpty();
    }

    // ---- Pneus ----
    @Test
    void tires_coldFrontAxle_raisesFrontPressure() {
        final var agg = with(b -> b.tires(wheels(160.0, 160.0, 190.0, 190.0)));

        final var s = guide(advisor.advise(agg, RWD).guides(), "pneus").suggestions();

        assertThat(s).anySatisfy(x -> {
            assertThat(x.axle()).isEqualTo("FRONT");
            assertThat(x.direction()).isEqualTo("INCREASE");
            assertThat(x.parameter()).containsIgnoringCase("pressão");
            assertThat(x.evidence()).contains("160");
        });
    }

    @Test
    void tires_hotRearAxle_lowersRearPressure() {
        final var agg = with(b -> b.tires(wheels(190.0, 190.0, 220.0, 220.0)));

        final var s = guide(advisor.advise(agg, RWD).guides(), "pneus").suggestions();

        assertThat(s).anySatisfy(x -> {
            assertThat(x.axle()).isEqualTo("REAR");
            assertThat(x.direction()).isEqualTo("DECREASE");
        });
    }

    @Test
    void tires_axleImbalanceAbove15F_lowersThePressureOfTheHotterAxleOnly() {
        final var agg = with(b -> b.tires(wheels(185.0, 185.0, 204.0, 204.0)));

        final var s = guide(advisor.advise(agg, RWD).guides(), "pneus").suggestions();

        assertThat(s).hasSize(1);
        assertThat(s.get(0).axle()).isEqualTo("REAR");
        assertThat(s.get(0).direction()).isEqualTo("DECREASE");
    }

    @Test
    void tires_withinTheIdealWindow_isOk() {
        final var agg = with(b -> b.tires(wheels(182.0, 184.0, 195.0, 196.0)));

        assertThat(guide(advisor.advise(agg, RWD).guides(), "pneus").status()).isEqualTo("OK");
    }

    // ---- Câmbio ----
    @Test
    void gearing_topGearHittingTheLimiter_suggestsALongerFinalDrive() {
        final var agg = with(b -> b.limiter(Map.of("1", 0.0, "2", 0.0, "3", 2.0, "4", 6.0, "5", 9.5, "6", 14.0)));

        final var g = guide(advisor.advise(agg, RWD).guides(), "cambio");

        assertThat(g.status()).isEqualTo("ADJUST");
        assertThat(g.suggestions()).anySatisfy(x -> {
            assertThat(x.parameter()).containsIgnoringCase("final");
            assertThat(x.direction()).isEqualTo("DECREASE");
            assertThat(x.evidence()).contains("14");
        });
    }

    @Test
    void gearing_topGearIsTheHighestNumberedGear_notTheLexicographicallyLastKey() {
        final var agg = with(b -> b.limiter(Map.of("2", 0.0, "9", 12.0, "10", 0.0)));

        final var s = guide(advisor.advise(agg, RWD).guides(), "cambio").suggestions();

        assertThat(s).noneSatisfy(x -> assertThat(x.parameter()).containsIgnoringCase("final"));
    }

    @Test
    void gearing_rarelyReachingTheLimiter_isOk() {
        assertThat(guide(advisor.advise(healthy(), RWD).guides(), "cambio").status()).isEqualTo("OK");
    }

    // ---- Balanço em curva: alinhamento, barras, molas ----
    @Test
    void midCornerUndersteer_softensFrontAntiRollBar_andAddsFrontNegativeCamber() {
        final var agg = with(b -> b.phases(new Phase(600, 10, 10), new Phase(700, 62.0, 4.0), new Phase(600, 10, 10)));
        final var guides = advisor.advise(agg, RWD).guides();

        assertThat(guide(guides, "barras").suggestions()).anySatisfy(x -> {
            assertThat(x.parameter()).containsIgnoringCase("barra dianteira");
            assertThat(x.direction()).isEqualTo("DECREASE");
            assertThat(x.evidence()).contains("62");
        });
        assertThat(guide(guides, "alinhamento").suggestions()).anySatisfy(x -> {
            assertThat(x.parameter()).containsIgnoringCase("cambagem dianteira");
            assertThat(x.direction()).isEqualTo("DECREASE");
        });
    }

    @Test
    void entryUndersteer_softensFrontSpring_andOpensFrontToe() {
        final var agg = with(b -> b.phases(new Phase(700, 58.0, 5.0), new Phase(600, 10, 10), new Phase(600, 10, 10)));
        final var guides = advisor.advise(agg, RWD).guides();

        assertThat(guide(guides, "molas").suggestions()).anySatisfy(x -> {
            assertThat(x.parameter()).containsIgnoringCase("mola dianteira");
            assertThat(x.direction()).isEqualTo("DECREASE");
        });
        assertThat(guide(guides, "alinhamento").suggestions()).anySatisfy(x -> assertThat(x.parameter()).containsIgnoringCase("convergência dianteira"));
    }

    @Test
    void exitUndersteer_stiffensTheRearAntiRollBar() {
        final var agg = with(b -> b.phases(new Phase(600, 10, 10), new Phase(600, 10, 10), new Phase(650, 55.0, 3.0)));

        assertThat(guide(advisor.advise(agg, RWD).guides(), "barras").suggestions()).anySatisfy(x -> {
            assertThat(x.parameter()).containsIgnoringCase("barra traseira");
            assertThat(x.direction()).isEqualTo("INCREASE");
        });
    }

    @Test
    void entryOversteer_softensTheRearAntiRollBar() {
        final var agg = with(b -> b.phases(new Phase(650, 4.0, 52.0), new Phase(600, 10, 10), new Phase(600, 10, 10)));

        assertThat(guide(advisor.advise(agg, RWD).guides(), "barras").suggestions()).anySatisfy(x -> {
            assertThat(x.parameter()).containsIgnoringCase("barra traseira");
            assertThat(x.direction()).isEqualTo("DECREASE");
        });
    }

    @Test
    void exitOversteer_softensTheRearSpringAndBar() {
        final var agg = with(b -> b.phases(new Phase(600, 10, 10), new Phase(600, 10, 10), new Phase(650, 3.0, 60.0)));
        final var guides = advisor.advise(agg, RWD).guides();

        assertThat(guide(guides, "molas").suggestions()).anySatisfy(x -> assertThat(x.parameter()).containsIgnoringCase("mola traseira"));
        assertThat(guide(guides, "barras").suggestions()).anySatisfy(x -> assertThat(x.parameter()).containsIgnoringCase("barra traseira"));
    }

    @Test
    void balanceGapBelowTheThreshold_isIgnored() {
        final var agg = with(b -> b.phases(new Phase(600, 30.0, 18.0), new Phase(600, 25.0, 15.0), new Phase(600, 22.0, 20.0)));

        assertThat(all(advisor.advise(agg, RWD).guides())).isEmpty();
    }

    @Test
    void phasesWithTooFewSamples_neverProduceAdvice_andTheGuidesSayThereIsNoSignal() {
        final var agg = with(b -> b.phases(new Phase(40, 90.0, 0.0), new Phase(40, 90.0, 0.0), new Phase(40, 90.0, 0.0)));
        final var guides = advisor.advise(agg, RWD).guides();

        assertThat(all(guides)).isEmpty();
        assertThat(guide(guides, "barras").status()).isEqualTo("NO_SIGNAL");
        assertThat(guide(guides, "alinhamento").status()).isEqualTo("NO_SIGNAL");
    }

    // ---- Suspensão: bottoming ----
    @Test
    void bottomingOnAnAxle_raisesRideHeightAndCompression_onThatAxleOnly() {
        final var agg = with(b -> b.bottoming(wheels(1.0, 1.0, 7.5, 6.5)));
        final var guides = advisor.advise(agg, RWD).guides();

        assertThat(guide(guides, "molas").suggestions()).anySatisfy(x -> {
            assertThat(x.axle()).isEqualTo("REAR");
            assertThat(x.direction()).isEqualTo("INCREASE");
            assertThat(x.parameter()).containsIgnoringCase("altura");
        });
        assertThat(guide(guides, "amortecimento").suggestions()).anySatisfy(x -> {
            assertThat(x.axle()).isEqualTo("REAR");
            assertThat(x.parameter()).containsIgnoringCase("compressão");
            assertThat(x.direction()).isEqualTo("INCREASE");
        });
        assertThat(all(guides)).noneSatisfy(x -> assertThat(x.axle()).isEqualTo("FRONT"));
    }

    @Test
    void bottomingUnder3Percent_isFine() {
        final var agg = with(b -> b.bottoming(wheels(2.9)));

        assertThat(guide(advisor.advise(agg, RWD).guides(), "molas").status()).isEqualTo("OK");
    }

    // ---- Freios ----
    @Test
    void frontLockups_lowerBrakePressure_orMoveBalanceRearward() {
        final var agg = with(b -> b.braking(500, 14.0, 1.0));

        final var s = guide(advisor.advise(agg, RWD).guides(), "freios").suggestions();

        assertThat(s).anySatisfy(x -> {
            assertThat(x.parameter()).containsIgnoringCase("pressão");
            assertThat(x.direction()).isEqualTo("DECREASE");
        });
    }

    @Test
    void rearLockups_moveBrakeBalanceForward() {
        final var agg = with(b -> b.braking(500, 1.0, 12.0));

        assertThat(guide(advisor.advise(agg, RWD).guides(), "freios").suggestions()).anySatisfy(x -> {
            assertThat(x.parameter()).containsIgnoringCase("equilíbrio");
            assertThat(x.direction()).isEqualTo("INCREASE");
        });
    }

    @Test
    void tooFewBrakingSamples_noBrakeAdvice() {
        final var agg = with(b -> b.braking(30, 50.0, 50.0));

        assertThat(guide(advisor.advise(agg, RWD).guides(), "freios").suggestions()).isEmpty();
        assertThat(guide(advisor.advise(agg, RWD).guides(), "freios").status()).isEqualTo("NO_SIGNAL");
    }

    // ---- Diferencial ----
    @Test
    void wheelspinOnRwd_lowersTheRearDifferentialAcceleration() {
        final var agg = with(b -> b.traction(700, 28.0, Map.of("1", 45.0, "2", 30.0, "3", 8.0)));

        assertThat(guide(advisor.advise(agg, RWD).guides(), "diferencial").suggestions()).anySatisfy(x -> {
            assertThat(x.axle()).isEqualTo("REAR");
            assertThat(x.parameter()).containsIgnoringCase("aceleração");
            assertThat(x.direction()).isEqualTo("DECREASE");
        });
    }

    @Test
    void wheelspinOnFwd_targetsTheFrontDifferential() {
        final var agg = with(b -> b.traction(700, 28.0, Map.of("1", 45.0)));

        assertThat(guide(advisor.advise(agg, FWD).guides(), "diferencial").suggestions()).anySatisfy(x -> assertThat(x.axle()).isEqualTo("FRONT"));
    }

    @Test
    void wheelspinOnAwd_targetsTheRearDifferential() {
        final var agg = with(b -> b.traction(700, 28.0, Map.of("1", 45.0)));

        assertThat(guide(advisor.advise(agg, AWD).guides(), "diferencial").suggestions()).anySatisfy(x -> assertThat(x.axle()).isEqualTo("REAR"));
    }

    @Test
    void entryUndersteer_lowersDecelerationOfTheDrivenAxle_andEntryOversteerRaisesIt() {
        final var under = with(b -> b.phases(new Phase(700, 60.0, 4.0), new Phase(600, 10, 10), new Phase(600, 10, 10)));
        final var over = with(b -> b.phases(new Phase(700, 4.0, 60.0), new Phase(600, 10, 10), new Phase(600, 10, 10)));

        assertThat(guide(advisor.advise(under, RWD).guides(), "diferencial").suggestions()).anySatisfy(x -> {
            assertThat(x.parameter()).containsIgnoringCase("desaceleração");
            assertThat(x.direction()).isEqualTo("DECREASE");
        });
        assertThat(guide(advisor.advise(over, RWD).guides(), "diferencial").suggestions()).anySatisfy(x -> {
            assertThat(x.parameter()).containsIgnoringCase("desaceleração");
            assertThat(x.direction()).isEqualTo("INCREASE");
        });
    }

    // ---- Priorização: no máximo 3 por ciclo ----
    @Test
    void thisCycle_hasAtMostThreeSuggestionsAndAtMostOnePerGuide_orderedBySeverity() {
        final var agg = with(b -> b
                .tires(wheels(150.0, 150.0, 225.0, 225.0))
                .bottoming(wheels(9.0))
                .braking(500, 20.0, 15.0)
                .traction(700, 35.0, Map.of("1", 60.0))
                .limiter(Map.of("5", 5.0, "6", 20.0))
                .phases(new Phase(700, 70.0, 2.0), new Phase(700, 70.0, 2.0), new Phase(700, 2.0, 70.0)));

        final var ranked = advisor.advise(agg, RWD);

        assertThat(ranked.thisCycle()).hasSizeLessThanOrEqualTo(3).hasSize(3);
        assertThat(ranked.thisCycle()).extracting(TuningSuggestionDTO::guide).doesNotHaveDuplicates();
        assertThat(ranked.thisCycle()).allSatisfy(x -> assertThat(x.thisCycle()).isTrue());
        assertThat(ranked.thisCycle()).extracting(TuningSuggestionDTO::priority).containsExactly(1, 2, 3);
        final var everything = all(ranked.guides());
        assertThat(everything).extracting(TuningSuggestionDTO::priority).doesNotHaveDuplicates();
        assertThat(everything.stream().filter(TuningSuggestionDTO::thisCycle).count()).isEqualTo(3);
    }

    @Test
    void thisCycle_neverRepeatsAGuide_evenWhenOneGuideHoldsSeveralOfTheMostSevereSuggestions() {
        // pneus: dianteiros frios E traseiros quentes (2 sugestões, severidade 2.0 cada); diferencial: patinagem forte (4.0)
        final var agg = with(b -> b.tires(wheels(150.0, 150.0, 230.0, 230.0)).traction(700, 60.0, Map.of("1", 80.0)));

        final var advice = advisor.advise(agg, RWD);

        assertThat(guide(advice.guides(), "pneus").suggestions()).hasSize(2);
        assertThat(advice.thisCycle()).extracting(TuningSuggestionDTO::guide).containsExactly("diferencial", "pneus");
        assertThat(guide(advice.guides(), "pneus").suggestions().stream().filter(TuningSuggestionDTO::thisCycle)).hasSize(1);
        assertThat(advice.thisCycle()).extracting(TuningSuggestionDTO::priority).containsExactly(1, 2);
    }

    @Test
    void thisCycle_isEmptyWhenThereIsNothingToAdjust() {
        final var ranked = advisor.advise(healthy(), RWD);

        assertThat(ranked.thisCycle()).isEmpty();
    }

    @Test
    void everySuggestionExplainsWhy_withEvidenceFromTheData() {
        final var agg = with(b -> b.tires(wheels(150.0)).bottoming(wheels(9.0)).braking(500, 20.0, 15.0));

        final var everything = all(advisor.advise(agg, RWD).guides());

        assertThat(everything).isNotEmpty().allSatisfy(x -> {
            assertThat(x.rationale()).isNotBlank();
            assertThat(x.evidence()).isNotBlank().containsPattern("\\d");
            assertThat(x.direction()).isIn("INCREASE", "DECREASE");
            assertThat(x.axle()).isIn("FRONT", "REAR", "BOTH", "NONE");
        });
    }
}
