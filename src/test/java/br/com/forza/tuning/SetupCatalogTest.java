package br.com.forza.tuning;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.forza.tuning.SetupCatalog.Field;
import br.com.forza.tuning.SetupCatalog.Range;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SetupCatalogTest {

    /** Setup completo e válido de um carro de 6 marchas, com molas/aero do carro entre 20 e 200. */
    private static Map<String, Double> validValues() {
        final Map<String, Double> values = new HashMap<>();
        for (final Field f : SetupCatalog.fields(6)) {
            values.put(f.key(), f.perCar() ? 100.0 : (f.min() + f.max()) / 2.0);
        }
        return values;
    }

    private static Map<String, Range> validRanges() {
        return new HashMap<>(Map.of(
                "spring_front", new Range(20, 200), "spring_rear", new Range(20, 200),
                "downforce_front", new Range(20, 200), "downforce_rear", new Range(20, 200)));
    }

    @Test
    void gearCountGeneratesOneFieldPerGear() {
        assertThat(SetupCatalog.fields(6).stream().filter(f -> f.key().startsWith("gear_"))).hasSize(6);
        assertThat(SetupCatalog.fields(10).stream().filter(f -> f.key().startsWith("gear_"))).hasSize(10);
    }

    @Test
    void fixedLimitsFollowTheGameValues() {
        assertThat(limits("tire_pressure_front")).containsExactly(1.0, 3.8);
        assertThat(limits("final_drive")).containsExactly(2.20, 6.10);
        assertThat(limits("gear_1")).containsExactly(0.48, 6.0);
        assertThat(limits("camber_rear")).containsExactly(-5.0, 5.0);
        assertThat(limits("toe_front")).containsExactly(-5.0, 5.0);
        assertThat(limits("caster")).containsExactly(1.0, 7.0);
        assertThat(limits("arb_rear")).containsExactly(1.0, 65.0);
        assertThat(limits("rebound_front")).containsExactly(1.0, 20.0);
        assertThat(limits("bump_rear")).containsExactly(1.0, 20.0);
        assertThat(limits("brake_balance")).containsExactly(0.0, 100.0);
        assertThat(limits("brake_pressure")).containsExactly(0.0, 200.0);
        assertThat(limits("diff_decel_rear")).containsExactly(0.0, 100.0);
        assertThat(limits("diff_center")).containsExactly(0.0, 100.0);
    }

    @Test
    void springsAndAeroDependOnTheCar() {
        for (final String key : List.of("spring_front", "spring_rear", "downforce_front", "downforce_rear")) {
            final Field f = SetupCatalog.find(key, 6).orElseThrow();
            assertThat(f.perCar()).isTrue();
            assertThat(f.min()).isNull();
        }
    }

    @Test
    void mapsAdvisorSuggestionsToSetupKeys() {
        assertThat(SetupCatalog.keyFor("pneus", "Pressão dos pneus dianteiros", "FRONT")).contains("tire_pressure_front");
        assertThat(SetupCatalog.keyFor("pneus", "Pressão dos pneus traseiros", "REAR")).contains("tire_pressure_rear");
        assertThat(SetupCatalog.keyFor("cambio", "Relação final (transmissão final)", "NONE")).contains("final_drive");
        assertThat(SetupCatalog.keyFor("cambio", "Relação da 4ª marcha", "NONE")).contains("gear_4");
        assertThat(SetupCatalog.keyFor("cambio", "Relações da 1ª à 3ª marcha (mais longas)", "NONE")).isEmpty();
        assertThat(SetupCatalog.keyFor("alinhamento", "Cambagem dianteira (mais negativa)", "FRONT")).contains("camber_front");
        assertThat(SetupCatalog.keyFor("alinhamento", "Convergência dianteira (mais aberta / toe-out)", "FRONT")).contains("toe_front");
        assertThat(SetupCatalog.keyFor("barras", "Barra traseira (anti-rolagem)", "REAR")).contains("arb_rear");
        assertThat(SetupCatalog.keyFor("molas", "Mola dianteira", "FRONT")).contains("spring_front");
        assertThat(SetupCatalog.keyFor("molas", "Altura do solo traseira", "REAR")).isEmpty();
        assertThat(SetupCatalog.keyFor("amortecimento", "Rigidez de compressão dianteira", "FRONT")).contains("bump_front");
        assertThat(SetupCatalog.keyFor("amortecimento", "Rigidez de retorno traseira", "REAR")).contains("rebound_rear");
        assertThat(SetupCatalog.keyFor("freios", "Equilíbrio de freio", "NONE")).contains("brake_balance");
        assertThat(SetupCatalog.keyFor("freios", "Pressão de freio", "NONE")).contains("brake_pressure");
        assertThat(SetupCatalog.keyFor("diferencial", "Diferencial dianteiro — aceleração", "FRONT")).contains("diff_accel_front");
        assertThat(SetupCatalog.keyFor("diferencial", "Diferencial traseiro — desaceleração", "REAR")).contains("diff_decel_rear");
        assertThat(SetupCatalog.keyFor("diferencial", "Diferencial central (mais para a traseira)", "NONE")).contains("diff_center");
    }

    @Test
    void acceptsACompleteValidSetup() {
        assertThat(SetupCatalog.validate(6, validValues(), validRanges())).isEmpty();
    }

    @Test
    void rejectsValuesOutsideTheFixedLimits() {
        final Map<String, Double> values = validValues();
        values.put("tire_pressure_front", 4.0);
        values.put("brake_pressure", -1.0);

        assertThat(SetupCatalog.validate(6, values, validRanges()))
                .anyMatch(e -> e.contains("tire_pressure_front") && e.contains("1") && e.contains("3,8"))
                .anyMatch(e -> e.contains("brake_pressure"));
    }

    @Test
    void rejectsMissingValues() {
        final Map<String, Double> values = validValues();
        values.remove("caster");

        assertThat(SetupCatalog.validate(6, values, validRanges())).anyMatch(e -> e.contains("caster"));
    }

    @Test
    void requiresARangeForSpringsAndAero() {
        final Map<String, Range> ranges = validRanges();
        ranges.remove("spring_front");

        assertThat(SetupCatalog.validate(6, validValues(), ranges)).anyMatch(e -> e.contains("spring_front"));
    }

    @Test
    void rejectsAnInvalidRangeAndValuesOutsideIt() {
        final Map<String, Range> ranges = validRanges();
        ranges.put("spring_rear", new Range(100, 100));
        final Map<String, Double> values = validValues();
        values.put("downforce_front", 300.0);

        assertThat(SetupCatalog.validate(6, values, ranges))
                .anyMatch(e -> e.contains("spring_rear"))
                .anyMatch(e -> e.contains("downforce_front"));
    }

    @Test
    void rejectsInvalidGearCountAndUnknownKeys() {
        assertThat(SetupCatalog.validate(0, validValues(), validRanges())).anyMatch(e -> e.contains("marchas"));
        assertThat(SetupCatalog.validate(11, validValues(), validRanges())).anyMatch(e -> e.contains("marchas"));

        final Map<String, Double> values = validValues();
        values.put("gear_7", 1.0);      // o carro tem 6 marchas
        values.put("inventado", 1.0);
        assertThat(SetupCatalog.validate(6, values, validRanges()))
                .anyMatch(e -> e.contains("gear_7"))
                .anyMatch(e -> e.contains("inventado"));
    }

    private static List<Double> limits(final String key) {
        final Field f = SetupCatalog.find(key, 10).orElseThrow();
        return List.of(f.min(), f.max());
    }
}
