package br.com.forza.tuning;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.forza.models.dto.TuningRecommendationDTO.TuningSetupGroupDTO;
import br.com.forza.models.dto.TuningRecommendationDTO.TuningSetupItemDTO;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A configuração inicial do desenvolvedor (receita que ele aplica nos carros antes de afinar) — os valores são dele. */
class TuningBaselineTest {

    private final List<TuningSetupGroupDTO> setup = TuningBaseline.setup();

    private TuningSetupGroupDTO group(final String id) {
        return setup.stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    private TuningSetupItemDTO item(final String groupId, final String parameter) {
        return group(groupId).items().stream().filter(i -> i.parameter().equals(parameter)).findFirst().orElseThrow();
    }

    @Test
    void coversEveryTuningGuide_inTheSameOrderAsTheGameMenu() {
        assertThat(setup).extracting(TuningSetupGroupDTO::id)
                .containsExactly("pneus", "cambio", "alinhamento", "barras", "molas", "amortecimento", "aerodinamica", "freios", "diferencial");
        assertThat(setup).allSatisfy(g -> {
            assertThat(g.title()).isNotBlank();
            assertThat(g.items()).isNotEmpty();
        });
    }

    @Test
    void tires_areOneAndAHalfToTwoBar_byCarSize() {
        final var pressure = item("pneus", "Pressão dos pneus");

        assertThat(pressure.front()).isEqualTo("1,5 a 2,0 bar");
        assertThat(pressure.rear()).isEqualTo("1,5 a 2,0 bar");
        assertThat(pressure.note()).contains("1,5").contains("pequeno").contains("2,0").contains("grande");
    }

    @Test
    void gearing_isARecipeNotANumber_firstGearAround90() {
        final var gears = group("cambio").items().get(0);

        assertThat(gears.value()).contains("1ª marcha").contains("90 km/h");
        assertThat(gears.front()).isNull();
        assertThat(gears.rear()).isNull();
    }

    @Test
    void alignment_isAllZero_withCasterSevenAndATireTemperatureCheck() {
        assertThat(item("alinhamento", "Cambagem").front()).isEqualTo("0,0");
        assertThat(item("alinhamento", "Cambagem").rear()).isEqualTo("0,0");
        assertThat(item("alinhamento", "Cambagem").note()).containsIgnoringCase("temperatura");
        assertThat(item("alinhamento", "Convergência").front()).isEqualTo("0,0");
        assertThat(item("alinhamento", "Convergência").rear()).isEqualTo("0,0");
        assertThat(item("alinhamento", "Caster").value()).isEqualTo("7,0");
    }

    @Test
    void antiRollBars_areOneFront_sixtyFiveRear() {
        final var bars = item("barras", "Barras estabilizadoras");

        assertThat(bars.front()).isEqualTo("1");
        assertThat(bars.rear()).isEqualTo("65");
    }

    @Test
    void springs_areEightyAndEighty() {
        final var springs = item("molas", "Molas");

        assertThat(springs.front()).isEqualTo("80");
        assertThat(springs.rear()).isEqualTo("80");
    }

    @Test
    void damping_isNineRebound_threeBump_onBothAxles() {
        assertThat(item("amortecimento", "Rigidez de retorno").front()).isEqualTo("9");
        assertThat(item("amortecimento", "Rigidez de retorno").rear()).isEqualTo("9");
        assertThat(item("amortecimento", "Rigidez de compressão").front()).isEqualTo("3");
        assertThat(item("amortecimento", "Rigidez de compressão").rear()).isEqualTo("3");
    }

    @Test
    void aero_isMaxFront_andSeventyFivePercentOfTheBarRear() {
        final var aero = item("aerodinamica", "Aerodinâmica");

        assertThat(aero.front()).isEqualTo("máximo");
        assertThat(aero.rear()).isEqualTo("75% da barra");
    }

    @Test
    void brakes_areFortyFiveBalance_and105Pressure() {
        assertThat(item("freios", "Equilíbrio").value()).isEqualTo("45%");
        assertThat(item("freios", "Pressão").value()).isEqualTo("105%");
    }

    @Test
    void differential_isFullAccel_zeroDecel_withBalanceBetween70And80() {
        assertThat(item("diferencial", "Aceleração").front()).isEqualTo("100%");
        assertThat(item("diferencial", "Aceleração").rear()).isEqualTo("100%");
        assertThat(item("diferencial", "Desaceleração").front()).isEqualTo("0%");
        assertThat(item("diferencial", "Desaceleração").rear()).isEqualTo("0%");
        assertThat(item("diferencial", "Equilíbrio").value()).isEqualTo("70 a 80%");
        assertThat(item("diferencial", "Aceleração").note()).containsIgnoringCase("eixo");
    }

    @Test
    void everyItemShowsAValueSomewhere() {
        assertThat(setup).flatExtracting(TuningSetupGroupDTO::items).allSatisfy(i ->
                assertThat(i.value() != null || i.front() != null || i.rear() != null).isTrue());
    }
}
