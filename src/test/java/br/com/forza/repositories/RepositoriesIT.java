package br.com.forza.repositories;

import static br.com.forza.support.Fixtures.sample;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.forza.models.entities.SessionMeta;
import br.com.forza.support.Fixtures;
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
}
