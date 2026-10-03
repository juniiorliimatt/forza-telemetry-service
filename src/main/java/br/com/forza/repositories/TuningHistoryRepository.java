package br.com.forza.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Histórico de tunings salvos ({@code forza.tuning_history}): foto da recomendação no momento em que a coleta foi reiniciada. */
@Repository
public class TuningHistoryRepository {

    private static final String LIST_COLUMNS = "id, game_format, car_ordinal, car_name, car_class, performance_index, drivetrain, created_at,"
            + " window_from, window_to, sessions, samples, adjustments";

    /** {@code recommendationJson} é nulo nas listagens (o JSON é pesado e só o detalhe precisa dele). */
    public record Entry(UUID id,
                        String gameFormat,
                        int carOrdinal,
                        String carName,
                        int carClass,
                        int performanceIndex,
                        String drivetrain,
                        Instant createdAt,
                        Instant windowFrom,
                        Instant windowTo,
                        int sessions,
                        long samples,
                        int adjustments,
                        String recommendationJson) {
    }

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<Entry> listMapper = (rs, rowNum) -> map(rs, null);

    private final RowMapper<Entry> detailMapper = (rs, rowNum) -> map(rs, rs.getString("recommendation"));

    public TuningHistoryRepository(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(final Entry entry) {
        jdbcTemplate.update("INSERT INTO forza.tuning_history (" + LIST_COLUMNS + ", recommendation) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                entry.id(), entry.gameFormat(), entry.carOrdinal(), entry.carName(), entry.carClass(), entry.performanceIndex(), entry.drivetrain(),
                utc(entry.createdAt()), utc(entry.windowFrom()), utc(entry.windowTo()), entry.sessions(), entry.samples(), entry.adjustments(),
                entry.recommendationJson());
    }

    /** Mais recentes primeiro, sem o JSON da recomendação. */
    public List<Entry> findAll(final String gameFormat) {
        return jdbcTemplate.query("SELECT " + LIST_COLUMNS + " FROM forza.tuning_history WHERE game_format = ? ORDER BY created_at DESC, id DESC",
                listMapper, gameFormat);
    }

    public Optional<Entry> findById(final UUID id) {
        return jdbcTemplate.query("SELECT " + LIST_COLUMNS + ", recommendation FROM forza.tuning_history WHERE id = ?", detailMapper, id).stream().findFirst();
    }

    private static Entry map(final ResultSet rs, final String json) throws SQLException {
        return new Entry(rs.getObject("id", UUID.class), rs.getString("game_format"), rs.getInt("car_ordinal"), rs.getString("car_name"),
                rs.getInt("car_class"), rs.getInt("performance_index"), rs.getString("drivetrain"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getObject("window_from", OffsetDateTime.class).toInstant(),
                rs.getObject("window_to", OffsetDateTime.class).toInstant(), rs.getInt("sessions"), rs.getLong("samples"), rs.getInt("adjustments"), json);
    }

    private static OffsetDateTime utc(final Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
