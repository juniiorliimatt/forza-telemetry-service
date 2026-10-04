package br.com.forza.repositories;

import br.com.forza.models.entities.SessionMeta;
import br.com.forza.telemetry.PerformanceClass;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Acesso JDBC a {@code forza.sessions}. JDBC puro (e não JPA) porque o caminho quente é
 * ingestão em lote de série temporal — ver {@link SampleRepository}.
 */
@Repository
public class SessionRepository {

    /** Sessão ativa ainda não tem sample_count gravado: conta ao vivo (só para ended_at IS NULL). */
    private static final String SELECT = """
            SELECT s.id, s.game_format, s.car_ordinal, s.car_class, s.performance_index, s.drivetrain,
                   s.num_cylinders, s.engine_max_rpm, s.engine_idle_rpm, s.track_ordinal, s.started_at, s.ended_at,
                   CASE WHEN s.ended_at IS NULL
                        THEN (SELECT COUNT(*) FROM forza.samples x WHERE x.session_id = s.id)
                        ELSE s.sample_count END AS sample_count,
                   s.summary::text AS summary, s.samples_purged_at
              FROM forza.sessions s
            """;

    /** Mesmo SELECT sem o texto do resumo (pesado) — pra listagens que só precisam dos metadados. */
    private static final String SELECT_NO_SUMMARY = SELECT.replace("s.summary::text AS summary", "NULL::text AS summary");

    private static final String ORDER = " ORDER BY s.started_at DESC, s.id DESC LIMIT ?";

    private static final RowMapper<SessionMeta> MAPPER = (rs, rowNum) -> {
        final int track = rs.getInt("track_ordinal");
        final Integer trackOrdinal = rs.wasNull() ? null : track;
        final OffsetDateTime endedAt = rs.getObject("ended_at", OffsetDateTime.class);
        final OffsetDateTime purgedAt = rs.getObject("samples_purged_at", OffsetDateTime.class);
        return new SessionMeta(
                rs.getObject("id", UUID.class),
                rs.getString("game_format"),
                rs.getInt("car_ordinal"),
                rs.getInt("car_class"),
                rs.getInt("performance_index"),
                rs.getInt("drivetrain"),
                rs.getInt("num_cylinders"),
                rs.getFloat("engine_max_rpm"),
                rs.getFloat("engine_idle_rpm"),
                trackOrdinal,
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                endedAt == null ? null : endedAt.toInstant(),
                rs.getInt("sample_count"),
                rs.getString("summary"),
                purgedAt == null ? null : purgedAt.toInstant());
    };

    private final JdbcTemplate jdbcTemplate;

    public SessionRepository(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(final SessionMeta meta) {
        jdbcTemplate.update("""
                        INSERT INTO forza.sessions (id, game_format, car_ordinal, car_class, performance_index, drivetrain,
                                                    num_cylinders, engine_max_rpm, engine_idle_rpm, track_ordinal, started_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                meta.id(), meta.gameFormat(), meta.carOrdinal(), meta.carClass(), meta.performanceIndex(),
                meta.drivetrain(), meta.cylinders(), meta.engineMaxRpm(), meta.engineIdleRpm(), meta.trackOrdinal(),
                utc(meta.startedAt()));
    }

    public void close(final UUID id, final Instant endedAt, final int sampleCount, final String summaryJson) {
        jdbcTemplate.update(
                "UPDATE forza.sessions SET ended_at = ?, sample_count = ?, summary = CAST(? AS jsonb) WHERE id = ?",
                utc(endedAt), sampleCount, summaryJson, id);
    }

    public void delete(final UUID id) {
        jdbcTemplate.update("DELETE FROM forza.sessions WHERE id = ?", id);
    }

    public Optional<SessionMeta> findById(final UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE s.id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * Sessões encerradas, com resumo gravado, de um carro/família de jogo e <b>classe de PI</b> iniciadas a partir de
     * {@code since}, mais recentes primeiro — base da recomendação de tuning (cada classe é uma build diferente).
     */
    public List<SessionMeta> findClosedForTuning(final String gameFormat, final int carOrdinal, final PerformanceClass performanceClass,
                                                 final Instant since, final int limit) {
        return jdbcTemplate.query(SELECT + " WHERE s.ended_at IS NOT NULL AND s.summary IS NOT NULL AND s.game_format = ? AND s.car_ordinal = ?"
                + " AND s.performance_index BETWEEN ? AND ?"
                + " AND s.started_at >= ? ORDER BY s.started_at DESC, s.id DESC LIMIT ?", MAPPER, gameFormat, carOrdinal,
                performanceClass.minPi(), performanceClass.maxPi(), utc(since), limit);
    }

    /**
     * Sessões encerradas de um carro/classe de PI cujas amostras ainda não foram apagadas, <b>menos as {@code keepNewest}
     * mais recentes</b> (essas continuam com telemetria de amostra) — o que a limpeza de {@code forza.samples} pode apagar.
     * Sessão aberta nunca entra. Conjunto pequeno (sessões de uma build), então {@code OFFSET} é inofensivo aqui.
     */
    public List<UUID> findSamplePurgeCandidates(final String gameFormat, final int carOrdinal, final PerformanceClass performanceClass,
                                                final int keepNewest) {
        return jdbcTemplate.queryForList("""
                SELECT s.id FROM forza.sessions s
                 WHERE s.ended_at IS NOT NULL AND s.samples_purged_at IS NULL AND s.game_format = ? AND s.car_ordinal = ?
                   AND s.performance_index BETWEEN ? AND ?
                 ORDER BY s.started_at DESC, s.id DESC OFFSET ?
                """, UUID.class, gameFormat, carOrdinal, performanceClass.minPi(), performanceClass.maxPi(), keepNewest);
    }

    /** Registra que as amostras dessas sessões foram apagadas ({@code summary}, voltas e {@code sample_count} ficam). */
    public void markSamplesPurged(final List<UUID> ids, final Instant at) {
        jdbcTemplate.batchUpdate("UPDATE forza.sessions SET samples_purged_at = ? WHERE id = ?",
                ids.stream().map(id -> new Object[]{utc(at), id}).toList());
    }

    /** Sessões em andamento (sem {@code ended_at}) de uma família de jogo, com a contagem de amostras ao vivo e sem o resumo. */
    public List<SessionMeta> findActiveMeta(final String gameFormat) {
        return jdbcTemplate.query(SELECT_NO_SUMMARY + " WHERE s.ended_at IS NULL AND s.game_format = ? ORDER BY s.started_at DESC, s.id DESC",
                MAPPER, gameFormat);
    }

    /** Metadados (sem o texto do resumo) das sessões encerradas com resumo de uma família de jogo. */
    public List<SessionMeta> findClosedMeta(final String gameFormat) {
        return jdbcTemplate.query(SELECT_NO_SUMMARY + " WHERE s.ended_at IS NOT NULL AND s.summary IS NOT NULL AND s.game_format = ?"
                + " ORDER BY s.started_at DESC, s.id DESC", MAPPER, gameFormat);
    }

    /** Paginação keyset por (started_at, id) decrescente — sem OFFSET. {@code cursorStartedAt} nulo = primeira página. */
    public List<SessionMeta> findPage(final Instant cursorStartedAt, final UUID cursorId, final int limit) {
        if (cursorStartedAt == null) {
            return jdbcTemplate.query(SELECT + ORDER, MAPPER, limit);
        }
        return jdbcTemplate.query(SELECT + " WHERE (s.started_at, s.id) < (?, ?)" + ORDER, MAPPER,
                utc(cursorStartedAt), cursorId, limit);
    }

    private static OffsetDateTime utc(final Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
