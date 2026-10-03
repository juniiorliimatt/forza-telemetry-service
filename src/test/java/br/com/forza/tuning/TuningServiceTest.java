package br.com.forza.tuning;

import static br.com.forza.telemetry.PerformanceClass.S2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.forza.config.TuningProperties;
import br.com.forza.exceptions.ResourceNotFoundException;
import br.com.forza.models.dto.TuningRecommendationDTO;
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
import br.com.forza.repositories.TuningHistoryRepository;
import br.com.forza.telemetry.CarCatalog;
import br.com.forza.telemetry.PerformanceClass;
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
import org.mockito.ArgumentCaptor;

class TuningServiceTest {

    private static final String HORIZON = "FH4/FH5/FH6";
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();   // como o do Spring: com java.time
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final TuningCheckpointRepository checkpoints = mock(TuningCheckpointRepository.class);
    private final TuningHistoryRepository history = mock(TuningHistoryRepository.class);
    private final TuningProperties properties = new TuningProperties(10, 6000, 20, 5000);
    private final TuningService service = new TuningService(sessions, checkpoints, history, new TuningAggregator(), new TuningAdvisor(),
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
        when(checkpoints.find(eq(HORIZON), anyInt(), any())).thenReturn(Optional.empty());

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
        when(checkpoints.find(HORIZON, 3667, S2)).thenReturn(Optional.of(NOW.minusSeconds(5 * 60L + 30)));   // só as 5 mais recentes (1..5 min)

        final var cars = service.cars();

        assertThat(cars.get(0).sessions()).isEqualTo(5);
        assertThat(cars.get(0).ready()).isFalse();
    }

    @Test
    void recommendation_notEnoughSessions_explainsWhatIsMissing_andReturnsNoGuides() throws Exception {
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), eq(S2), any(), anyInt())).thenReturn(sessionsOf(3667, 7, 2000, 0));
        when(checkpoints.find(HORIZON, 3667, S2)).thenReturn(Optional.empty());

        final var rec = service.recommendation(3667, S2);

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
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), eq(S2), any(), anyInt())).thenReturn(sessionsOf(3667, 12, 200, 0));
        when(checkpoints.find(HORIZON, 3667, S2)).thenReturn(Optional.empty());

        final var rec = service.recommendation(3667, S2);

        assertThat(rec.readiness().ready()).isFalse();
        assertThat(rec.readiness().samples()).isEqualTo(2400);
        assertThat(rec.readiness().requiredSamples()).isEqualTo(6000);
        assertThat(rec.readiness().missing()).anySatisfy(m -> assertThat(m).containsIgnoringCase("amostras").doesNotContainIgnoringCase("min"));
        assertThat(rec.guides()).isEmpty();
    }

    @Test
    void recommendation_ready_returnsEveryGuide_andTheCycleSuggestions() throws Exception {
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), eq(S2), any(), anyInt())).thenReturn(sessionsOf(3667, 12, 1000, 9.0));
        when(checkpoints.find(HORIZON, 3667, S2)).thenReturn(Optional.empty());

        final var rec = service.recommendation(3667, S2);

        assertThat(rec.readiness().ready()).isTrue();
        assertThat(rec.readiness().missing()).isEmpty();
        assertThat(rec.guides()).hasSize(9);
        assertThat(rec.thisCycle()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        assertThat(rec.drivetrain()).isEqualTo("RWD");
        assertThat(rec.windowFrom()).isBefore(rec.windowTo());
    }

    @Test
    void recommendation_ready_withNothingToAdjust_stillListsAllGuides() throws Exception {
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), eq(S2), any(), anyInt())).thenReturn(sessionsOf(3667, 12, 1000, 0));
        when(checkpoints.find(HORIZON, 3667, S2)).thenReturn(Optional.empty());

        final var rec = service.recommendation(3667, S2);

        assertThat(rec.guides()).hasSize(9);
        assertThat(rec.thisCycle()).isEmpty();
        assertThat(rec.guides()).extracting("status").doesNotContain("ADJUST");
    }

    @Test
    void recommendation_asksForAtMostTheConfiguredWindow_andSinceTheCheckpoint() throws Exception {
        final var checkpoint = NOW.minusSeconds(3600);
        when(checkpoints.find(HORIZON, 3667, S2)).thenReturn(Optional.of(checkpoint));
        when(sessions.findClosedForTuning(HORIZON, 3667, S2, checkpoint, 20)).thenReturn(sessionsOf(3667, 12, 1000, 0));

        final var rec = service.recommendation(3667, S2);

        assertThat(rec.checkpointAt()).isEqualTo(checkpoint);
        verify(sessions).findClosedForTuning(HORIZON, 3667, S2, checkpoint, 20);
    }

    @Test
    void recommendation_withoutCheckpoint_usesTheEpochAsSince() throws Exception {
        when(checkpoints.find(HORIZON, 3667, S2)).thenReturn(Optional.empty());
        when(sessions.findClosedForTuning(HORIZON, 3667, S2, Instant.EPOCH, 20)).thenReturn(sessionsOf(3667, 1, 1000, 0));

        final var rec = service.recommendation(3667, S2);

        assertThat(rec.checkpointAt()).isNull();
    }

    @Test
    void recommendation_skipsSessionsWithUnreadableSummary() throws Exception {
        final var list = new ArrayList<>(sessionsOf(3667, 11, 1000, 0));
        list.add(meta(3667, 30, 1000, "{isto não é json", 1));
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), eq(S2), any(), anyInt())).thenReturn(list);
        when(checkpoints.find(HORIZON, 3667, S2)).thenReturn(Optional.empty());

        assertThat(service.recommendation(3667, S2).readiness().sessions()).isEqualTo(11);
    }

    @Test
    void recommendation_unknownCar_throwsNotFound() {
        when(checkpoints.find(HORIZON, 99, S2)).thenReturn(Optional.empty());
        when(sessions.findClosedForTuning(eq(HORIZON), eq(99), eq(S2), any(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service.recommendation(99, S2)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void resetCollection_storesTheCheckpointAtNow() {
        service.resetCollection(3667, S2);

        verify(checkpoints).upsert(HORIZON, 3667, S2, NOW);
    }

    @Test
    void resetCollection_withAReadyRecommendation_savesItToTheHistoryBeforeMovingTheCheckpoint() throws Exception {
        when(checkpoints.find(HORIZON, 3667, S2)).thenReturn(Optional.empty());
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), eq(S2), any(), anyInt())).thenReturn(sessionsOf(3667, 12, 1000, 9.0));

        service.resetCollection(3667, S2);

        final var entry = ArgumentCaptor.forClass(TuningHistoryRepository.Entry.class);
        final var order = inOrder(history, checkpoints);
        order.verify(history).insert(entry.capture());
        order.verify(checkpoints).upsert(HORIZON, 3667, S2, NOW);
        final var saved = entry.getValue();
        assertThat(saved.id()).isNotNull();
        assertThat(saved.gameFormat()).isEqualTo(HORIZON);
        assertThat(saved.carOrdinal()).isEqualTo(3667);
        assertThat(saved.carName()).isEqualTo("2021 Porsche 911 GT3");
        assertThat(saved.createdAt()).isEqualTo(NOW);
        assertThat(saved.sessions()).isEqualTo(12);
        assertThat(saved.samples()).isEqualTo(12_000);
        assertThat(saved.adjustments()).isPositive();
        assertThat(mapper.readValue(saved.recommendationJson(), TuningRecommendationDTO.class).guides()).hasSize(9);
    }

    @Test
    void resetCollection_withoutEnoughData_savesNothingButStillMovesTheCheckpoint() throws Exception {
        when(checkpoints.find(HORIZON, 3667, S2)).thenReturn(Optional.empty());
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), eq(S2), any(), anyInt())).thenReturn(sessionsOf(3667, 4, 1000, 9.0));

        service.resetCollection(3667, S2);

        verify(history, never()).insert(any());
        verify(checkpoints).upsert(HORIZON, 3667, S2, NOW);
    }

    @Test
    void resetCollection_withNoSessionsAtAll_savesNothingAndDoesNotFail() {
        when(checkpoints.find(HORIZON, 3667, S2)).thenReturn(Optional.empty());
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), eq(S2), any(), anyInt())).thenReturn(List.of());

        service.resetCollection(3667, S2);

        verify(history, never()).insert(any());
        verify(checkpoints).upsert(HORIZON, 3667, S2, NOW);
    }

    @Test
    void resetCollection_whenSavingTheHistoryFails_doesNotMoveTheCheckpoint() throws Exception {
        when(checkpoints.find(HORIZON, 3667, S2)).thenReturn(Optional.empty());
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), eq(S2), any(), anyInt())).thenReturn(sessionsOf(3667, 12, 1000, 9.0));
        doThrow(new IllegalStateException("banco fora")).when(history).insert(any());

        assertThatThrownBy(() -> service.resetCollection(3667, S2)).isInstanceOf(IllegalStateException.class);

        verify(checkpoints, never()).upsert(anyString(), anyInt(), any(), any());
    }

    @Test
    void history_listsTheSavedTunings_withoutTheHeavyRecommendation() {
        final var id = UUID.randomUUID();
        when(history.findAll(HORIZON)).thenReturn(List.of(new TuningHistoryRepository.Entry(id, HORIZON, 1105, "1964 Aston Martin DB5 Vantage", 3, 700, "RWD",
                NOW, NOW.minusSeconds(3600), NOW.minusSeconds(60), 12, 30_523L, 2, null)));

        final var items = service.history();

        assertThat(items).hasSize(1);
        final var item = items.get(0);
        assertThat(item.id()).isEqualTo(id);
        assertThat(item.carOrdinal()).isEqualTo(1105);
        assertThat(item.carName()).isEqualTo("1964 Aston Martin DB5 Vantage");
        assertThat(item.drivetrain()).isEqualTo("RWD");
        assertThat(item.savedAt()).isEqualTo(NOW);
        assertThat(item.sessions()).isEqualTo(12);
        assertThat(item.samples()).isEqualTo(30_523L);
        assertThat(item.adjustments()).isEqualTo(2);
    }

    @Test
    void historyEntry_returnsTheRecommendationAsItWasWhenSaved() throws Exception {
        when(checkpoints.find(HORIZON, 3667, S2)).thenReturn(Optional.empty());
        when(sessions.findClosedForTuning(eq(HORIZON), eq(3667), eq(S2), any(), anyInt())).thenReturn(sessionsOf(3667, 12, 1000, 9.0));
        service.resetCollection(3667, S2);
        final var captured = ArgumentCaptor.forClass(TuningHistoryRepository.Entry.class);
        verify(history).insert(captured.capture());
        when(history.findById(captured.getValue().id())).thenReturn(Optional.of(captured.getValue()));

        final var dto = service.historyEntry(captured.getValue().id());

        assertThat(dto.id()).isEqualTo(captured.getValue().id());
        assertThat(dto.savedAt()).isEqualTo(NOW);
        assertThat(dto.recommendation().carOrdinal()).isEqualTo(3667);
        assertThat(dto.recommendation().guides()).hasSize(9);
        assertThat(dto.recommendation().thisCycle()).hasSize(captured.getValue().adjustments());
    }

    private SessionMeta metaWithPi(final int car, final int pi, final int offsetMinutes, final int samples) throws Exception {
        final var start = NOW.minusSeconds(offsetMinutes * 60L);
        return new SessionMeta(UUID.randomUUID(), HORIZON, car, 3, pi, 1, 8, 8000f, 1000f, null, start, start.plusSeconds(300), samples, summaryJson(samples, 0));
    }

    @Test
    void cars_separatesTheSameCarByPerformanceClass_oneEntryPerBuild() throws Exception {
        final var all = new ArrayList<SessionMeta>();
        for (int i = 1; i <= 3; i++) {
            all.add(metaWithPi(1105, 700, i, 1000));    // A
        }
        for (int i = 4; i <= 5; i++) {
            all.add(metaWithPi(1105, 416, i, 500));     // C
        }
        when(sessions.findClosedMeta(HORIZON)).thenReturn(all);
        when(checkpoints.find(eq(HORIZON), anyInt(), any())).thenReturn(Optional.empty());

        final var cars = service.cars();

        assertThat(cars).hasSize(2);
        final var a = cars.stream().filter(c -> "A".equals(c.performanceClass())).findFirst().orElseThrow();
        final var c = cars.stream().filter(x -> "C".equals(x.performanceClass())).findFirst().orElseThrow();
        assertThat(a.sessions()).isEqualTo(3);
        assertThat(a.samples()).isEqualTo(3000);
        assertThat(a.performanceIndex()).isEqualTo(700);
        assertThat(c.sessions()).isEqualTo(2);
        assertThat(c.samples()).isEqualTo(1000);
        assertThat(c.performanceIndex()).isEqualTo(416);
    }

    private SessionMeta openSession(final int car, final int pi, final int startedMinutesAgo, final int samplesSoFar) {
        final var start = NOW.minusSeconds(startedMinutesAgo * 60L);
        return new SessionMeta(UUID.randomUUID(), HORIZON, car, 3, pi, 1, 8, 8000f, 1000f, null, start, null, samplesSoFar, null);
    }

    @Test
    void cars_showsTheSessionInProgress_withLiveSamplesAndTheTarget() throws Exception {
        final var all = new ArrayList<SessionMeta>();
        for (int i = 1; i <= 3; i++) {
            all.add(metaWithPi(1105, 700, i + 10, 1000));
        }
        when(sessions.findClosedMeta(HORIZON)).thenReturn(all);
        when(sessions.findActiveMeta(HORIZON)).thenReturn(List.of(openSession(1105, 700, 1, 2340)));
        when(checkpoints.find(eq(HORIZON), anyInt(), any())).thenReturn(Optional.empty());

        final var car = service.cars().get(0);

        assertThat(car.sessions()).as("a sessão aberta ainda não conta").isEqualTo(3);
        assertThat(car.samples()).isEqualTo(3000);
        assertThat(car.requiredSamples()).isEqualTo(6000);
        assertThat(car.activeSession()).isNotNull();
        assertThat(car.activeSession().samples()).isEqualTo(2340);
        assertThat(car.activeSession().targetSamples()).isEqualTo(5000);
        assertThat(car.activeSession().startedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void cars_withoutAnOpenSession_hasNoActiveSession() throws Exception {
        when(sessions.findClosedMeta(HORIZON)).thenReturn(List.of(metaWithPi(1105, 700, 1, 1000)));
        when(checkpoints.find(eq(HORIZON), anyInt(), any())).thenReturn(Optional.empty());

        assertThat(service.cars().get(0).activeSession()).isNull();
    }

    @Test
    void cars_aCarWithOnlyAnOpenSession_stillAppearsWithZeroProgress() {
        when(sessions.findClosedMeta(HORIZON)).thenReturn(List.of());
        when(sessions.findActiveMeta(HORIZON)).thenReturn(List.of(openSession(1105, 700, 1, 800)));
        when(checkpoints.find(eq(HORIZON), anyInt(), any())).thenReturn(Optional.empty());

        final var cars = service.cars();

        assertThat(cars).hasSize(1);
        assertThat(cars.get(0).carName()).isEqualTo("1964 Aston Martin DB5 Vantage");
        assertThat(cars.get(0).performanceClass()).isEqualTo("A");
        assertThat(cars.get(0).sessions()).isZero();
        assertThat(cars.get(0).samples()).isZero();
        assertThat(cars.get(0).ready()).isFalse();
        assertThat(cars.get(0).activeSession().samples()).isEqualTo(800);
    }

    @Test
    void cars_anOpenSessionStartedBeforeTheCheckpoint_isIgnored() {
        when(sessions.findClosedMeta(HORIZON)).thenReturn(List.of());
        when(sessions.findActiveMeta(HORIZON)).thenReturn(List.of(openSession(1105, 700, 10, 800)));
        when(checkpoints.find(eq(HORIZON), anyInt(), any())).thenReturn(Optional.of(NOW.minusSeconds(60)));   // marco depois do início

        assertThat(service.cars()).isEmpty();
    }

    @Test
    void cars_theOpenSessionBelongsToItsOwnPerformanceClass() throws Exception {
        when(sessions.findClosedMeta(HORIZON)).thenReturn(List.of(metaWithPi(1105, 700, 5, 1000), metaWithPi(1105, 416, 6, 1000)));
        when(sessions.findActiveMeta(HORIZON)).thenReturn(List.of(openSession(1105, 416, 1, 300)));
        when(checkpoints.find(eq(HORIZON), anyInt(), any())).thenReturn(Optional.empty());

        final var cars = service.cars();

        assertThat(cars.stream().filter(c -> "A".equals(c.performanceClass())).findFirst().orElseThrow().activeSession()).isNull();
        assertThat(cars.stream().filter(c -> "C".equals(c.performanceClass())).findFirst().orElseThrow().activeSession().samples()).isEqualTo(300);
    }

    @Test
    void cars_eachClassUsesItsOwnCheckpoint() throws Exception {
        final var all = new ArrayList<SessionMeta>();
        for (int i = 1; i <= 4; i++) {
            all.add(metaWithPi(1105, 700, i, 1000));
            all.add(metaWithPi(1105, 416, i, 1000));
        }
        when(sessions.findClosedMeta(HORIZON)).thenReturn(all);
        when(checkpoints.find(HORIZON, 1105, PerformanceClass.A)).thenReturn(Optional.of(NOW.minusSeconds(2 * 60L + 30)));   // só 1 e 2 min
        when(checkpoints.find(HORIZON, 1105, PerformanceClass.C)).thenReturn(Optional.empty());

        final var cars = service.cars();

        assertThat(cars.stream().filter(c -> "A".equals(c.performanceClass())).findFirst().orElseThrow().sessions()).isEqualTo(2);
        assertThat(cars.stream().filter(c -> "C".equals(c.performanceClass())).findFirst().orElseThrow().sessions()).isEqualTo(4);
    }

    @Test
    void recommendation_reportsThePerformanceClassOfTheBuild() throws Exception {
        when(checkpoints.find(HORIZON, 1105, PerformanceClass.A)).thenReturn(Optional.empty());
        when(sessions.findClosedForTuning(eq(HORIZON), eq(1105), eq(PerformanceClass.A), any(), anyInt())).thenReturn(List.of(metaWithPi(1105, 700, 1, 1000)));

        final var rec = service.recommendation(1105, PerformanceClass.A);

        assertThat(rec.performanceClass()).isEqualTo("A");
        assertThat(rec.performanceIndex()).isEqualTo(700);
    }

    @Test
    void resetCollection_onlyMovesTheMarkerOfTheGivenClass() throws Exception {
        when(checkpoints.find(HORIZON, 1105, PerformanceClass.A)).thenReturn(Optional.empty());
        when(sessions.findClosedForTuning(eq(HORIZON), eq(1105), eq(PerformanceClass.A), any(), anyInt())).thenReturn(List.of());

        service.resetCollection(1105, PerformanceClass.A);

        verify(checkpoints).upsert(HORIZON, 1105, PerformanceClass.A, NOW);
        verify(checkpoints, never()).upsert(eq(HORIZON), eq(1105), eq(PerformanceClass.C), any());
    }

    @Test
    void history_exposesThePerformanceClassOfEachSavedTuning() {
        when(history.findAll(HORIZON)).thenReturn(List.of(
                new TuningHistoryRepository.Entry(UUID.randomUUID(), HORIZON, 1105, "Aston", 3, 700, "RWD", NOW, NOW, NOW, 10, 50_000L, 1, null),
                new TuningHistoryRepository.Entry(UUID.randomUUID(), HORIZON, 1105, "Aston", 1, 416, "RWD", NOW, NOW, NOW, 10, 50_000L, 1, null)));

        assertThat(service.history()).extracting("performanceClass").containsExactly("A", "C");
    }

    @Test
    void historyEntry_ofASnapshotSavedBeforeClassesExisted_getsTheClassFromThePi() throws Exception {
        final var id = UUID.randomUUID();
        final var legacyJson = "{\"carOrdinal\":1105,\"carName\":\"Aston\",\"carClass\":3,\"performanceIndex\":700,\"drivetrain\":\"RWD\","
                + "\"readiness\":{\"ready\":true,\"sessions\":12,\"requiredSessions\":10,\"samples\":30000,\"requiredSamples\":6000,\"missing\":[]},"
                + "\"windowFrom\":null,\"windowTo\":null,\"checkpointAt\":null,\"guides\":[],\"thisCycle\":[]}";
        when(history.findById(id)).thenReturn(Optional.of(new TuningHistoryRepository.Entry(id, HORIZON, 1105, "Aston", 3, 700, "RWD", NOW, NOW, NOW, 12, 30_000L, 0, legacyJson)));

        assertThat(service.historyEntry(id).recommendation().performanceClass()).isEqualTo("A");
    }

    @Test
    void historyEntry_unknownId_throwsNotFound() {
        final var id = UUID.randomUUID();
        when(history.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.historyEntry(id)).isInstanceOf(ResourceNotFoundException.class);
    }
}
