package br.com.forza.tuning;

import static br.com.forza.telemetry.PerformanceClass.S2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.forza.exceptions.InvalidSetupException;
import br.com.forza.exceptions.ResourceNotFoundException;
import br.com.forza.exceptions.SetupConflictException;
import br.com.forza.models.dto.SetupDTO;
import br.com.forza.models.dto.SetupRequest;
import br.com.forza.models.dto.TuningRecommendationDTO;
import br.com.forza.models.dto.TuningRecommendationDTO.TuningReadinessDTO;
import br.com.forza.models.dto.TuningRecommendationDTO.TuningSuggestionDTO;
import br.com.forza.tuning.CarSetupStore.CarSetup;
import br.com.forza.tuning.SetupCatalog.Range;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SetupServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final Map<String, Range> RANGES = Map.of("spring_front", new Range(20, 220), "spring_rear", new Range(20, 220),
            "downforce_front", new Range(20, 220), "downforce_rear", new Range(20, 220));

    private final CarSetupStore store = mock(CarSetupStore.class);
    private final TuningService tuning = mock(TuningService.class);
    private final SetupService service = new SetupService(store, tuning, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Map<String, Double> validValues() {
        final Map<String, Double> values = new HashMap<>();
        SetupCatalog.fields(6).forEach(f -> values.put(f.key(), f.perCar() ? 100.0 : (f.min() + f.max()) / 2.0));
        return values;
    }

    private static CarSetup existing() {
        return new CarSetup(6, validValues(), new HashMap<>(RANGES), NOW.minusSeconds(3600));
    }

    private static SetupRequest request(final Map<String, Double> values) {
        return new SetupRequest(6, values, new HashMap<>(RANGES));
    }

    @Test
    void get_withoutASetup_returnsTheBaselineDraftToBeConfirmed() {
        when(store.find(3667, S2)).thenReturn(Optional.empty());

        final SetupDTO dto = service.get(3667, S2);

        assertThat(dto.confirmed()).isFalse();
        assertThat(dto.confirmedAt()).isNull();
        assertThat(dto.gearCount()).isEqualTo(6);
        assertThat(dto.values()).containsEntry("tire_pressure_front", 1.75).containsEntry("brake_balance", 45.0).containsEntry("brake_pressure", 105.0)
                .containsEntry("diff_accel_rear", 100.0).containsEntry("diff_decel_rear", 0.0).containsEntry("caster", 7.0);
        assertThat(dto.values()).doesNotContainKeys("final_drive", "gear_1", "downforce_front");   // sem receita: o usuário informa
        assertThat(dto.ranges()).isEmpty();
        assertThat(dto.fields()).extracting(SetupDTO.FieldDTO::key).contains("tire_pressure_front", "gear_6").doesNotContain("gear_7");
    }

    @Test
    void get_withASetup_returnsItAsConfirmed() {
        when(store.find(3667, S2)).thenReturn(Optional.of(existing()));

        final SetupDTO dto = service.get(3667, S2);

        assertThat(dto.confirmed()).isTrue();
        assertThat(dto.confirmedAt()).isEqualTo(NOW.minusSeconds(3600));
        assertThat(dto.values()).isEqualTo(validValues());
        assertThat(dto.ranges()).isEqualTo(RANGES);
    }

    @Test
    void save_firstTime_resetsTheCollectionAndThenStoresTheSetup() {
        when(store.find(3667, S2)).thenReturn(Optional.empty());

        final SetupDTO dto = service.save(3667, S2, request(validValues()));

        final var order = inOrder(tuning, store);
        order.verify(tuning).resetCollection(3667, S2);   // a foto do histórico sai ANTES de o setup mudar
        final ArgumentCaptor<CarSetup> saved = ArgumentCaptor.forClass(CarSetup.class);
        order.verify(store).save(eq(3667), eq(S2), saved.capture());
        assertThat(saved.getValue().confirmedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().gearCount()).isEqualTo(6);
        assertThat(dto.confirmed()).isTrue();
    }

    @Test
    void save_invalid_isRejectedWithEveryErrorAndStoresNothing() {
        final Map<String, Double> values = validValues();
        values.put("tire_pressure_front", 9.0);
        values.remove("caster");

        assertThatThrownBy(() -> service.save(3667, S2, request(values))).isInstanceOfSatisfying(InvalidSetupException.class,
                e -> assertThat(e.errors()).hasSize(2));
        verify(store, never()).save(any(Integer.class), any(), any());
        verify(tuning, never()).resetCollection(any(Integer.class), any());
    }

    @Test
    void save_unchanged_keepsTheCollectionRunning() {
        when(store.find(3667, S2)).thenReturn(Optional.of(existing()));

        service.save(3667, S2, request(validValues()));

        verify(tuning, never()).resetCollection(any(Integer.class), any());
        verify(store, never()).save(any(Integer.class), any(), any());
    }

    @Test
    void save_withAChangedValue_restartsTheCollection() {
        when(store.find(3667, S2)).thenReturn(Optional.of(existing()));
        final Map<String, Double> values = validValues();
        values.put("arb_front", 10.0);

        service.save(3667, S2, request(values));

        verify(tuning).resetCollection(3667, S2);
        final ArgumentCaptor<CarSetup> saved = ArgumentCaptor.forClass(CarSetup.class);
        verify(store).save(eq(3667), eq(S2), saved.capture());
        assertThat(saved.getValue().values()).containsEntry("arb_front", 10.0);
    }

    @Test
    void apply_writesTheCycleTargetsAsTheNewCurrentValuesAndRestartsTheCollection() {
        when(store.find(3667, S2)).thenReturn(Optional.of(existing()));
        final double pressure = existing().values().get("tire_pressure_front");
        final var applied = new TuningSuggestionDTO(1, true, "pneus", "Pressão dos pneus dianteiros", "FRONT", "DECREASE", "r", "e", 0.1, "bar", "SMALL",
                "tire_pressure_front", pressure, pressure - 0.1, false);
        final var noTarget = new TuningSuggestionDTO(2, true, "molas", "Altura do solo traseira", "REAR", "INCREASE", "r", "e", 0.5, "cm", "SMALL");
        when(tuning.recommendation(3667, S2)).thenReturn(recommendation(List.of(applied, noTarget)));

        final SetupDTO dto = service.apply(3667, S2);

        assertThat(dto.values().get("tire_pressure_front")).isEqualTo(pressure - 0.1, org.assertj.core.data.Offset.offset(1e-9));
        verify(tuning).resetCollection(3667, S2);
        verify(store).save(eq(3667), eq(S2), any());
    }

    @Test
    void apply_withoutAConfirmedSetup_isAConflict() {
        when(store.find(3667, S2)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(3667, S2)).isInstanceOf(SetupConflictException.class);
        verify(tuning, never()).resetCollection(any(Integer.class), any());
    }

    @Test
    void apply_whenNoSuggestionHasATarget_isAConflict() {
        when(store.find(3667, S2)).thenReturn(Optional.of(existing()));
        final var noTarget = new TuningSuggestionDTO(1, true, "molas", "Altura do solo traseira", "REAR", "INCREASE", "r", "e", 0.5, "cm", "SMALL");
        when(tuning.recommendation(3667, S2)).thenReturn(recommendation(List.of(noTarget)));

        assertThatThrownBy(() -> service.apply(3667, S2)).isInstanceOf(SetupConflictException.class);
        verify(tuning, never()).resetCollection(any(Integer.class), any());
    }

    @Test
    void delete_removesTheSetup_andNotFoundWhenThereIsNone() {
        when(store.delete(3667, S2)).thenReturn(true);
        service.delete(3667, S2);
        verify(store).delete(3667, S2);

        when(store.delete(1, S2)).thenReturn(false);
        assertThatThrownBy(() -> service.delete(1, S2)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static TuningRecommendationDTO recommendation(final List<TuningSuggestionDTO> cycle) {
        return new TuningRecommendationDTO(3667, "Carro", 4, 812, "S2", "RWD", new TuningReadinessDTO(true, 12, 5, 12000, 20000, false, 10, 50000, List.of()),
                null, null, null, List.of(), cycle, List.of(), true);
    }
}
