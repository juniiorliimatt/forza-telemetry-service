package br.com.forza.repositories;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Marco de coleta de tuning por carro e família de jogo ({@code forza.tuning_checkpoints}). */
@Repository
public class TuningCheckpointRepository {

    private final JdbcTemplate jdbcTemplate;

    public TuningCheckpointRepository(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Instant> find(final String gameFormat, final int carOrdinal) {
        return jdbcTemplate.query("SELECT since FROM forza.tuning_checkpoints WHERE game_format = ? AND car_ordinal = ?",
                (rs, rowNum) -> rs.getObject("since", OffsetDateTime.class).toInstant(), gameFormat, carOrdinal).stream().findFirst();
    }

    public void upsert(final String gameFormat, final int carOrdinal, final Instant since) {
        jdbcTemplate.update("""
                INSERT INTO forza.tuning_checkpoints (game_format, car_ordinal, since) VALUES (?, ?, ?)
                ON CONFLICT (game_format, car_ordinal) DO UPDATE SET since = EXCLUDED.since
                """, gameFormat, carOrdinal, OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
    }
}
