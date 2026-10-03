package br.com.forza.repositories;

import static br.com.forza.support.Fixtures.sample;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.forza.models.entities.SessionMeta;
import br.com.forza.support.Fixtures;
import br.com.forza.telemetry.PerformanceClass;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Repositórios JDBC contra um Postgres 18 real e descartável (nunca o {@code workbox-postgres}
 * de dev), com o role/schema restritos de produção e as migrations reais do Liquibase.
 * Exige Docker. O listener UDP/worker fica desligado pra não abrir a porta 5310.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "telemetry.udp.enabled=false")
@ActiveProfiles("dev")
class RepositoriesIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
            .withDatabaseName("workbox")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("testcontainers-init.sql");

    @DynamicPropertySource
    static void datasourceProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://%s:%d/workbox?reWriteBatchedInserts=true"
                .formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432)));
        registry.add("spring.datasource.username", () -> "forza_service");
        registry.add("spring.datasource.password", () -> "forza_service");
    }

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private SampleRepository samples;

    @Autowired
    private LapRepository laps;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TuningCheckpointRepository checkpoints;

    @Autowired
    private TuningHistoryRepository history;

    private SessionMeta newSession(final Instant startedAt) {
        final var base = Fixtures.session(UUID.randomUUID(), 2, null);
        return new SessionMeta(base.id(), base.gameFormat(), 1234, 5, 800, 2, 8, 8000f, 1000f, 77, startedAt, null, 0, null);
    }

    @Test
    void session_insertThenFind_roundTripsAllColumnsAndIsActive() {
        final var meta = newSession(Instant.parse("2026-10-03T12:00:00.123456Z"));

        sessions.insert(meta);
        final var found = sessions.findById(meta.id()).orElseThrow();

        assertThat(found.carOrdinal()).isEqualTo(1234);
        assertThat(found.drivetrain()).isEqualTo(2);
        assertThat(found.trackOrdinal()).isEqualTo(77);
        assertThat(found.engineMaxRpm()).isEqualTo(8000f);
        assertThat(found.startedAt()).isEqualTo(Instant.parse("2026-10-03T12:00:00.123456Z"));
        assertThat(found.endedAt()).isNull();
        assertThat(found.summaryJson()).isNull();
    }

    @Test
    void session_nullTrackOrdinal_isReadBackAsNull() {
        final var base = newSession(Instant.parse("2026-10-03T12:01:00Z"));
        final var meta = new SessionMeta(base.id(), base.gameFormat(), 1, 1, 1, 0, 4, 1f, 1f, null, base.startedAt(), null, 0, null);

        sessions.insert(meta);

        assertThat(sessions.findById(meta.id()).orElseThrow().trackOrdinal()).isNull();
    }

    @Test
    void session_activeSessionCountsSamplesLive_closedUsesStoredCount() {
        final var meta = newSession(Instant.parse("2026-10-03T12:02:00Z"));
        sessions.insert(meta);
        samples.batchInsert(meta.id(), List.of(sample().tMs(0).build(), sample().tMs(50).build(), sample().tMs(100).build()));

        assertThat(sessions.findById(meta.id()).orElseThrow().sampleCount()).isEqualTo(3);

        sessions.close(meta.id(), Instant.parse("2026-10-03T12:03:00Z"), 99, "{\"samples\":99}");
        final var closed = sessions.findById(meta.id()).orElseThrow();
        assertThat(closed.sampleCount()).isEqualTo(99);
        assertThat(closed.endedAt()).isEqualTo(Instant.parse("2026-10-03T12:03:00Z"));
        assertThat(closed.summaryJson()).contains("\"samples\"").contains("99");
    }

    @Test
    void session_deleteCascadesToSamplesAndLaps() {
        final var meta = newSession(Instant.parse("2026-10-03T12:04:00Z"));
        sessions.insert(meta);
        samples.batchInsert(meta.id(), List.of(sample().tMs(0).build()));
        laps.upsert(meta.id(), 1, 60f);

        sessions.delete(meta.id());

        assertThat(sessions.findById(meta.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM forza.samples WHERE session_id = ?", Integer.class, meta.id())).isZero();
        assertThat(laps.findBySession(meta.id())).isEmpty();
    }

    @Test
    void session_findPage_keysetPaginatesNewestFirstWithoutGapsOrDuplicates() {
        jdbc.update("DELETE FROM forza.sessions");
        final var base = Instant.parse("2026-10-04T10:00:00Z");
        final List<UUID> idsNewestFirst = new ArrayList<>();
        for (int i = 4; i >= 0; i--) {
            final var meta = newSession(base.plusSeconds(i * 60L));
            sessions.insert(meta);
            idsNewestFirst.add(meta.id());
        }

        final var first = sessions.findPage(null, null, 2);
        final var second = sessions.findPage(first.get(1).startedAt(), first.get(1).id(), 2);
        final var third = sessions.findPage(second.get(1).startedAt(), second.get(1).id(), 2);

        final List<UUID> all = new ArrayList<>();
        first.forEach(s -> all.add(s.id()));
        second.forEach(s -> all.add(s.id()));
        third.forEach(s -> all.add(s.id()));
        assertThat(all).containsExactlyElementsOf(idsNewestFirst);
        assertThat(third).hasSize(1);
    }

    @Test
    void session_findPage_sameStartedAtIsOrderedAndPagedById() {
        jdbc.update("DELETE FROM forza.sessions");
        final var at = Instant.parse("2026-10-05T10:00:00Z");
        for (int i = 0; i < 3; i++) {
            sessions.insert(newSession(at));
        }

        final var first = sessions.findPage(null, null, 2);
        final var rest = sessions.findPage(first.get(1).startedAt(), first.get(1).id(), 2);

        assertThat(first).hasSize(2);
        assertThat(rest).hasSize(1);
        assertThat(rest.get(0).id()).isNotIn(first.get(0).id(), first.get(1).id());
    }

    @Test
    void samples_roundTripWheelArraysAndNullTireWear() {
        final var meta = newSession(Instant.parse("2026-10-03T12:05:00Z"));
        sessions.insert(meta);
        samples.batchInsert(meta.id(), List.of(
                sample().tMs(0).gear(4).accel(200).brake(10).steer(-30).susp(0.1f, 0.2f, 0.3f, 0.4f)
                        .slipRatio(1f, 2f, 3f, 4f).slipAngle(-1f, -2f, 3f, 4f).tireTemp(180f, 181f, 190f, 191f)
                        .onRumble(true).build(),
                sample().tMs(50).tireWear(0.1f, 0.2f, 0.3f, 0.4f).build()));

        final var rows = samples.findAll(meta.id());

        assertThat(rows).hasSize(2);
        final var first = rows.get(0);
        assertThat(first.gear()).isEqualTo(4);
        assertThat(first.steer()).isEqualTo(-30);
        assertThat(first.onRumble()).isTrue();
        assertThat(first.susp()).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(first.slipAngle()).containsExactly(-1f, -2f, 3f, 4f);
        assertThat(first.tireTemp()).containsExactly(180f, 181f, 190f, 191f);
        assertThat(first.tireWear()).isNull();
        assertThat(rows.get(1).tireWear()).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
    }

    @Test
    void samples_duplicateTimestampIsIgnoredNotFailed() {
        final var meta = newSession(Instant.parse("2026-10-03T12:06:00Z"));
        sessions.insert(meta);

        samples.batchInsert(meta.id(), List.of(sample().tMs(10).gear(1).build()));
        samples.batchInsert(meta.id(), List.of(sample().tMs(10).gear(9).build(), sample().tMs(20).build()));

        final var rows = samples.findAll(meta.id());
        assertThat(rows).extracting("tMs").containsExactly(10, 20);
        assertThat(rows.get(0).gear()).isEqualTo(1);
    }

    @Test
    void samples_findRange_filtersByWindowOrdersByTimeAndAppliesLimit() {
        final var meta = newSession(Instant.parse("2026-10-03T12:07:00Z"));
        sessions.insert(meta);
        final var rows = new ArrayList<br.com.forza.models.entities.SampleRow>();
        for (int t = 0; t <= 500; t += 50) {
            rows.add(sample().tMs(t).build());
        }
        samples.batchInsert(meta.id(), rows);

        assertThat(samples.findRange(meta.id(), 100, 300, 100)).extracting("tMs").containsExactly(100, 150, 200, 250, 300);
        assertThat(samples.findRange(meta.id(), 400, null, 100)).extracting("tMs").containsExactly(400, 450, 500);
        assertThat(samples.findRange(meta.id(), 0, null, 3)).extracting("tMs").containsExactly(0, 50, 100);
        assertThat(samples.findRange(meta.id(), 9999, null, 10)).isEmpty();
    }

    @Test
    void samples_emptyBatchIsANoOp() {
        final var meta = newSession(Instant.parse("2026-10-03T12:08:00Z"));
        sessions.insert(meta);

        samples.batchInsert(meta.id(), List.of());

        assertThat(samples.findAll(meta.id())).isEmpty();
    }

    @Test
    void laps_upsertOverwritesSameLapAndListsInOrder() {
        final var meta = newSession(Instant.parse("2026-10-03T12:09:00Z"));
        sessions.insert(meta);

        laps.upsert(meta.id(), 2, 61.25f);
        laps.upsert(meta.id(), 1, 62.5f);
        laps.upsert(meta.id(), 1, 60.0f);

        assertThat(laps.findBySession(meta.id())).extracting("lapNumber", "lapTimeS")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 60.0f), org.assertj.core.groups.Tuple.tuple(2, 61.25f));
    }

    private SessionMeta closedSession(final int car, final String format, final Instant startedAt, final String summaryJson) {
        return closedSession(car, format, 800, startedAt, summaryJson);
    }

    private SessionMeta closedSession(final int car, final String format, final int pi, final Instant startedAt, final String summaryJson) {
        final var base = Fixtures.session(UUID.randomUUID(), 1, null);
        final var meta = new SessionMeta(base.id(), format, car, 4, pi, 1, 8, 8000f, 1000f, null, startedAt, null, 0, null);
        sessions.insert(meta);
        sessions.close(meta.id(), startedAt.plusSeconds(60), 100, summaryJson);
        return meta;
    }

    @Test
    void tuning_findClosedForTuning_filtersByCarFormatSummaryAndCheckpoint_newestFirst_withLimit() {
        jdbc.update("DELETE FROM forza.sessions");
        final var base = Instant.parse("2026-11-01T10:00:00Z");
        final var old = closedSession(3667, "FH4/FH5/FH6", base, "{\"samples\":1}");
        final var mid = closedSession(3667, "FH4/FH5/FH6", base.plusSeconds(600), "{\"samples\":2}");
        final var recent = closedSession(3667, "FH4/FH5/FH6", base.plusSeconds(1200), "{\"samples\":3}");
        closedSession(3667, "FH4/FH5/FH6", base.plusSeconds(1800), null);                 // sem resumo
        closedSession(3667, "FM2023-Dash", base.plusSeconds(1900), "{\"samples\":4}");   // outro jogo
        closedSession(111, "FH4/FH5/FH6", base.plusSeconds(2000), "{\"samples\":5}");    // outro carro
        sessions.insert(newSession(base.plusSeconds(2100)));                              // ativa

        final var all = sessions.findClosedForTuning("FH4/FH5/FH6", 3667, PerformanceClass.S1, Instant.EPOCH, 10);
        final var since = sessions.findClosedForTuning("FH4/FH5/FH6", 3667, PerformanceClass.S1, base.plusSeconds(300), 10);
        final var limited = sessions.findClosedForTuning("FH4/FH5/FH6", 3667, PerformanceClass.S1, Instant.EPOCH, 2);

        assertThat(all).extracting(SessionMeta::id).containsExactly(recent.id(), mid.id(), old.id());
        assertThat(all.get(0).summaryJson()).contains("\"samples\"");
        assertThat(since).extracting(SessionMeta::id).containsExactly(recent.id(), mid.id());
        assertThat(limited).extracting(SessionMeta::id).containsExactly(recent.id(), mid.id());
    }

    @Test
    void samples_maxTMs_returnsTheLastSampleTime_orMinusOneForAnEmptySession() {
        final var meta = newSession(Instant.parse("2026-11-07T10:00:00Z"));
        sessions.insert(meta);

        assertThat(samples.maxTMs(meta.id())).isEqualTo(-1);

        samples.batchInsert(meta.id(), List.of(sample().tMs(0).build(), sample().tMs(50).build(), sample().tMs(40_000).build()));

        assertThat(samples.maxTMs(meta.id())).isEqualTo(40_000);
        assertThat(samples.maxTMs(UUID.randomUUID())).isEqualTo(-1);
    }

    @Test
    void tuning_findActiveMeta_returnsOnlyOpenSessionsOfTheFormat_withTheLiveSampleCount() {
        jdbc.update("DELETE FROM forza.sessions");
        final var base = Instant.parse("2026-11-06T10:00:00Z");
        final var open = newSession(base);
        sessions.insert(open);
        samples.batchInsert(open.id(), List.of(sample().tMs(0).build(), sample().tMs(50).build(), sample().tMs(100).build()));
        closedSession(1234, "FH4/FH5/FH6", base.plusSeconds(600), "{\"samples\":1}");                  // já encerrada
        final var otherGame = newSession(base.plusSeconds(1200));
        sessions.insert(new SessionMeta(otherGame.id(), "FM2023-Dash", 1234, 5, 800, 2, 8, 8000f, 1000f, 77, otherGame.startedAt(), null, 0, null));   // outro jogo

        final var active = sessions.findActiveMeta("FH4/FH5/FH6");

        assertThat(active).extracting(SessionMeta::id).containsExactly(open.id());
        assertThat(active.get(0).sampleCount()).isEqualTo(3);
        assertThat(active.get(0).endedAt()).isNull();
        assertThat(active.get(0).summaryJson()).isNull();
    }

    @Test
    void tuning_findClosedForTuning_separatesBuildsByPerformanceClass_boundariesIncluded() {
        jdbc.update("DELETE FROM forza.sessions");
        final var base = Instant.parse("2026-11-05T10:00:00Z");
        final var a600 = closedSession(1105, "FH4/FH5/FH6", 601, base, "{\"samples\":1}");              // A (piso)
        final var a700 = closedSession(1105, "FH4/FH5/FH6", 700, base.plusSeconds(600), "{\"samples\":2}");   // A (teto)
        final var s1 = closedSession(1105, "FH4/FH5/FH6", 701, base.plusSeconds(1200), "{\"samples\":3}");    // S1 (piso)
        final var c = closedSession(1105, "FH4/FH5/FH6", 416, base.plusSeconds(1800), "{\"samples\":4}");     // C

        final var classA = sessions.findClosedForTuning("FH4/FH5/FH6", 1105, PerformanceClass.A, Instant.EPOCH, 10);
        final var classS1 = sessions.findClosedForTuning("FH4/FH5/FH6", 1105, PerformanceClass.S1, Instant.EPOCH, 10);
        final var classC = sessions.findClosedForTuning("FH4/FH5/FH6", 1105, PerformanceClass.C, Instant.EPOCH, 10);
        final var classR = sessions.findClosedForTuning("FH4/FH5/FH6", 1105, PerformanceClass.R, Instant.EPOCH, 10);

        assertThat(classA).extracting(SessionMeta::id).containsExactly(a700.id(), a600.id());
        assertThat(classS1).extracting(SessionMeta::id).containsExactly(s1.id());
        assertThat(classC).extracting(SessionMeta::id).containsExactly(c.id());
        assertThat(classR).isEmpty();
    }

    @Test
    void tuning_findClosedMeta_returnsClosedSessionsWithSummaryOfTheFormat_withoutTheHeavySummaryText() {
        jdbc.update("DELETE FROM forza.sessions");
        final var base = Instant.parse("2026-11-02T10:00:00Z");
        final var a = closedSession(3667, "FH4/FH5/FH6", base, "{\"samples\":1}");
        closedSession(3667, "FM2023-Dash", base.plusSeconds(60), "{\"samples\":1}");
        closedSession(3667, "FH4/FH5/FH6", base.plusSeconds(120), null);

        final var found = sessions.findClosedMeta("FH4/FH5/FH6");

        assertThat(found).extracting(SessionMeta::id).containsExactly(a.id());
        assertThat(found.get(0).summaryJson()).isNull();
        assertThat(found.get(0).sampleCount()).isEqualTo(100);
    }

    @Test
    void tuning_checkpoints_upsertAndFindPerCarClassAndFormat() {
        assertThat(checkpoints.find("FH4/FH5/FH6", 777, PerformanceClass.A)).isEmpty();

        checkpoints.upsert("FH4/FH5/FH6", 777, PerformanceClass.A, Instant.parse("2026-11-03T10:00:00Z"));
        checkpoints.upsert("FH4/FH5/FH6", 777, PerformanceClass.A, Instant.parse("2026-11-04T10:00:00.123456Z"));

        assertThat(checkpoints.find("FH4/FH5/FH6", 777, PerformanceClass.A)).contains(Instant.parse("2026-11-04T10:00:00.123456Z"));
        assertThat(checkpoints.find("FH4/FH5/FH6", 777, PerformanceClass.S1)).as("outra classe do mesmo carro").isEmpty();
        assertThat(checkpoints.find("FM2023-Dash", 777, PerformanceClass.A)).isEmpty();
        assertThat(checkpoints.find("FH4/FH5/FH6", 778, PerformanceClass.A)).isEmpty();
    }

    @Test
    void tuning_checkpoints_eachClassOfTheSameCarKeepsItsOwnMarker() {
        checkpoints.upsert("FH4/FH5/FH6", 790, PerformanceClass.A, Instant.parse("2026-11-03T10:00:00Z"));
        checkpoints.upsert("FH4/FH5/FH6", 790, PerformanceClass.C, Instant.parse("2026-11-04T10:00:00Z"));

        assertThat(checkpoints.find("FH4/FH5/FH6", 790, PerformanceClass.A)).contains(Instant.parse("2026-11-03T10:00:00Z"));
        assertThat(checkpoints.find("FH4/FH5/FH6", 790, PerformanceClass.C)).contains(Instant.parse("2026-11-04T10:00:00Z"));
    }

    private static TuningHistoryRepository.Entry historyEntry(final UUID id, final String format, final Instant savedAt, final String json) {
        return new TuningHistoryRepository.Entry(id, format, 1105, "1964 Aston Martin DB5 Vantage", 3, 700, "RWD", savedAt,
                Instant.parse("2026-10-03T19:02:00.123456Z"), Instant.parse("2026-10-03T19:28:00Z"), 12, 30_523L, 2, json);
    }

    @Test
    void tuning_history_insertThenFindById_roundTripsTheJsonbAndAllColumns() {
        final var id = UUID.randomUUID();
        final var json = "{\"guides\":[{\"id\":\"pneus\",\"notes\":[\"áéí ç\"]}],\"thisCycle\":[]}";

        history.insert(historyEntry(id, "FH4/FH5/FH6", Instant.parse("2026-10-03T19:50:00.654321Z"), json));
        final var found = history.findById(id).orElseThrow();

        assertThat(found.carOrdinal()).isEqualTo(1105);
        assertThat(found.carName()).isEqualTo("1964 Aston Martin DB5 Vantage");
        assertThat(found.carClass()).isEqualTo(3);
        assertThat(found.performanceIndex()).isEqualTo(700);
        assertThat(found.drivetrain()).isEqualTo("RWD");
        assertThat(found.createdAt()).isEqualTo(Instant.parse("2026-10-03T19:50:00.654321Z"));
        assertThat(found.windowFrom()).isEqualTo(Instant.parse("2026-10-03T19:02:00.123456Z"));
        assertThat(found.sessions()).isEqualTo(12);
        assertThat(found.samples()).isEqualTo(30_523L);
        assertThat(found.adjustments()).isEqualTo(2);
        assertThat(found.recommendationJson()).contains("\"pneus\"").contains("áéí ç");
        assertThat(history.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void tuning_history_findAll_filtersByFormat_newestFirst_withoutTheJson() {
        final var older = UUID.randomUUID();
        final var newer = UUID.randomUUID();
        history.insert(historyEntry(older, "FH4/FH5/FH6", Instant.parse("2026-10-03T10:00:00Z"), "{}"));
        history.insert(historyEntry(newer, "FH4/FH5/FH6", Instant.parse("2026-10-04T10:00:00Z"), "{}"));
        history.insert(historyEntry(UUID.randomUUID(), "FM2023-Dash", Instant.parse("2026-10-05T10:00:00Z"), "{}"));

        final var all = history.findAll("FH4/FH5/FH6");

        assertThat(all).extracting(TuningHistoryRepository.Entry::id).containsSubsequence(newer, older);
        assertThat(all).allSatisfy(e -> {
            assertThat(e.gameFormat()).isEqualTo("FH4/FH5/FH6");
            assertThat(e.recommendationJson()).isNull();
        });
    }
}
