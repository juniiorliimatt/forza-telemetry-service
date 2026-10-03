package br.com.forza.tuning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.forza.config.TuningProperties;
import br.com.forza.exceptions.ResourceNotFoundException;
import br.com.forza.models.dto.TuningSummaryDTO;
import br.com.forza.models.dto.TuningSummaryDTO.Braking;
import br.com.forza.models.dto.TuningSummaryDTO.CornerBalance;
import br.com.forza.models.dto.TuningSummaryDTO.CornerPhase;
import br.com.forza.models.dto.TuningSummaryDTO.Engine;
import br.com.forza.models.dto.TuningSummaryDTO.Gear;
import br.com.forza.models.dto.TuningSummaryDTO.SpeedKmh;
import br.com.forza.models.dto.TuningSummaryDTO.SuspensionWheel;
import br.com.forza.models.dto.TuningSummaryDTO.TireWheel;
import br.com.forza.models.dto.TuningSummaryDTO.Traction;
import br.com.forza.models.entities.SessionMeta;
import br.com.forza.repositories.SessionRepository;
import br.com.forza.repositories.TuningCheckpointRepository;
import br.com.forza.telemetry.CarCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TuningServiceTest {

    private static final String HORIZON = "FH4/FH5/FH6";
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final TuningCheckpointRepository checkpoints = mock(TuningCheckpointRepository.class);
    private final TuningProperties properties = new TuningProperties(10, 6000, 20);
    private final TuningService service = new TuningService(sessions, checkpoints, new TuningAggregator(), new TuningAdvisor(),
            new CarCatalog(mapper), mapper, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Map<String, SuspensionWheel> susp(final double bottoming) {
        final var w = new SuspensionWheel(0.5, 0.8, bottoming, 0.0);
        return Map.of("FL", w, "FR", w, "RL", w, "RR", w);
    }

    private static Map<String, TireWheel> tires(final double temp) {
        final var w = new TireWheel(temp, temp + 10, null);
        return Map.of("FL", w, "FR", w, "RL", w, "RR", w);
    }

    private String summaryJson(final int samples, final double bottoming) throws Exception {
        final var empty = new CornerPhase(0, null, null, null, null);
        final var summary = new TuningSummaryDTO(samples, samples / 20.0, susp(bottoming), new SpeedKmh(250, 120),
                new Engine(8000, 500, 7000, 600, 5000, 10, Map.of("6", new Gear(50.0, 6000, 1.0))), tires(190),
                new CornerBalance(empty, empty, empty, null), new Braking(300, 1.0, 1.0), new Traction(300, 5.0, Map.of("1", 5.0)), null, 1.0);
        return mapper.writeValueAsString(summary);
    }

    private SessionMeta meta(final int car, final int offsetMinutes, final int samples, final String summaryJson, final int drivetrain) {
        final var start = NOW.minusSeconds(offsetMinutes * 60L);
        return new SessionMeta(UUID.randomUUID(), HORIZON, car, 4, 812, drivetrain, 8, 8000f, 1000f, null, start, start.plusSeconds(300), samples, summaryJson);
    }

    private List<SessionMeta> sessionsOf(final int car, final int count, final int samplesEach, final double bottoming) throws Exception {
        final List<SessionMeta> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(meta(car, i + 1, samplesEach, summaryJson(samplesEach, bottoming), 1));
        }
        return list;
    }

    @Test
    void cars_groupsClosedSessionsByCar_withProgressAndName_mostRecentFirst() throws Exception {
        final var all = new ArrayList<SessionMeta>();
        all.addAll(sessionsOf(3667, 11, 1000, 0));   // pronto (11 x 1000 = 11000)
        all.addAll(sessionsOf(1234, 3, 500, 0));     // incompleto
        when(sessions.findClosedMeta(HORIZON)).thenReturn(all);
        when(checkpoints.find(eq(HORIZON), anyInt())).thenReturn(Optional.empty());

        final var cars = service.cars();

        assertThat(cars).hasSize(2);
        final var ready = cars.stream().filter(c -> c.carOrdinal() == 3667).findFirst().orElseThrow();
        assertThat(ready.carName()).isEqualTo("2021 Porsche 911 GT3");
        assertThat(ready.sessions()).isEqualTo(11);
        assertThat(ready.samples()).isEqualTo(11_000);
        assertThat(ready.requiredSessions()).isEqualTo(10);
        assertThat(ready.ready()).isTrue();
        assertThat(ready.drivetrain()).isEqualTo("RWD");
        final var notReady = cars.stream().filter(c -> c.carOrdinal() == 1234).findFirst().orElseThrow();
        assertThat(notReady.ready()).isFalse();
        assertThat(notReady.carName()).isNull();
        assertThat(cars.get(0).lastSessionAt()).isAfterOrEqualTo(cars.get(1).lastSessionAt());
    }

    @Test
    void cars_countsOnlySessionsAfterTheCheckpoint() throws Exception {
        final var all = sessionsOf(3667, 12, 1000, 0);
        when(sessions.findClosedMeta(HORIZON)).thenReturn(all);
        when(checkpoints.find(HORIZON, 3667)).thenReturn(Optional.of(NOW.minusSeconds(5 * 60L + 30)));   // só as 5 mais recentes (1..5 min)

        final var cars = service.cars();

        assertThat(cars.get(0).sessions()).isEqualTo(5);
        assertThat(cars.get(0).ready()).isFalse();
    }

    @Test
    void recommendation_notEnoughSessions_explainsWhatIsMissing_andReturnsNoGuides() throws Exception {
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), any(), anyInt())).thenReturn(sessionsOf(3667, 7, 2000, 0));
        when(checkpoints.find(HORIZON, 3667)).thenReturn(Optional.empty());

        final var rec = service.recommendation(3667);

        assertThat(rec.readiness().ready()).isFalse();
        assertThat(rec.readiness().sessions()).isEqualTo(7);
        assertThat(rec.readiness().requiredSessions()).isEqualTo(10);
        assertThat(rec.readiness().missing()).anySatisfy(m -> assertThat(m).contains("3").contains("sess"));
        assertThat(rec.guides()).isEmpty();
        assertThat(rec.thisCycle()).isEmpty();
        assertThat(rec.carName()).isEqualTo("2021 Porsche 911 GT3");
    }

    @Test
    void recommendation_enoughSessionsButTooFewSamples_isNotReady_andSaysSo() throws Exception {
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), any(), anyInt())).thenReturn(sessionsOf(3667, 12, 200, 0));
        when(checkpoints.find(HORIZON, 3667)).thenReturn(Optional.empty());

        final var rec = service.recommendation(3667);

        assertThat(rec.readiness().ready()).isFalse();
        assertThat(rec.readiness().samples()).isEqualTo(2400);
        assertThat(rec.readiness().requiredSamples()).isEqualTo(6000);
        assertThat(rec.readiness().missing()).anySatisfy(m -> assertThat(m).containsIgnoringCase("amostras"));
        assertThat(rec.guides()).isEmpty();
    }

    @Test
    void recommendation_ready_returnsEveryGuide_andTheCycleSuggestions() throws Exception {
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), any(), anyInt())).thenReturn(sessionsOf(3667, 12, 1000, 9.0));
        when(checkpoints.find(HORIZON, 3667)).thenReturn(Optional.empty());

        final var rec = service.recommendation(3667);

        assertThat(rec.readiness().ready()).isTrue();
        assertThat(rec.readiness().missing()).isEmpty();
        assertThat(rec.guides()).hasSize(9);
        assertThat(rec.thisCycle()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        assertThat(rec.drivetrain()).isEqualTo("RWD");
        assertThat(rec.windowFrom()).isBefore(rec.windowTo());
    }

    @Test
    void recommendation_ready_withNothingToAdjust_stillListsAllGuides() throws Exception {
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), any(), anyInt())).thenReturn(sessionsOf(3667, 12, 1000, 0));
        when(checkpoints.find(HORIZON, 3667)).thenReturn(Optional.empty());

        final var rec = service.recommendation(3667);

        assertThat(rec.guides()).hasSize(9);
        assertThat(rec.thisCycle()).isEmpty();
        assertThat(rec.guides()).extracting("status").doesNotContain("ADJUST");
    }

    @Test
    void recommendation_asksForAtMostTheConfiguredWindow_andSinceTheCheckpoint() throws Exception {
        final var checkpoint = NOW.minusSeconds(3600);
        when(checkpoints.find(HORIZON, 3667)).thenReturn(Optional.of(checkpoint));
        when(sessions.findClosedForTuning(HORIZON, 3667, checkpoint, 20)).thenReturn(sessionsOf(3667, 12, 1000, 0));

        final var rec = service.recommendation(3667);

        assertThat(rec.checkpointAt()).isEqualTo(checkpoint);
        verify(sessions).findClosedForTuning(HORIZON, 3667, checkpoint, 20);
    }

    @Test
    void recommendation_withoutCheckpoint_usesTheEpochAsSince() throws Exception {
        when(checkpoints.find(HORIZON, 3667)).thenReturn(Optional.empty());
        when(sessions.findClosedForTuning(HORIZON, 3667, Instant.EPOCH, 20)).thenReturn(sessionsOf(3667, 1, 1000, 0));

        final var rec = service.recommendation(3667);

        assertThat(rec.checkpointAt()).isNull();
    }

    @Test
    void recommendation_skipsSessionsWithUnreadableSummary() throws Exception {
        final var list = new ArrayList<>(sessionsOf(3667, 11, 1000, 0));
        list.add(meta(3667, 30, 1000, "{isto não é json", 1));
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), any(), anyInt())).thenReturn(list);
        when(checkpoints.find(HORIZON, 3667)).thenReturn(Optional.empty());

        assertThat(service.recommendation(3667).readiness().sessions()).isEqualTo(11);
    }

    @Test
    void recommendation_unknownCar_throwsNotFound() {
        when(checkpoints.find(HORIZON, 99)).thenReturn(Optional.empty());
        when(sessions.findClosedForTuning(eq(HORIZON), eq(99), any(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service.recommendation(99)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void resetCollection_storesTheCheckpointAtNow() {
        service.resetCollection(3667);

        verify(checkpoints).upsert(HORIZON, 3667, NOW);
    }
}
