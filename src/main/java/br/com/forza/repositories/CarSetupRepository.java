package br.com.forza.repositories;

import br.com.forza.telemetry.PerformanceClass;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Setup atual de cada carro/classe de PI, informado pelo usuário ({@code forza.car_setups}); os JSONs são (de)serializados pelo serviço. */
@Repository
public class CarSetupRepository {

    public record Entry(String gameFormat, int carOrdinal, PerformanceClass performanceClass, int gearCount, String valuesJson, String rangesJson,
                        Instant confirmedAt) {
    }

    private final JdbcTemplate jdbcTemplate;

    public CarSetupRepository(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Entry> find(final String gameFormat, final int carOrdinal, final PerformanceClass performanceClass) {
        return jdbcTemplate.query("""
                SELECT game_format, car_ordinal, performance_class, gear_count, setup_values, ranges, confirmed_at
                FROM forza.car_setups WHERE game_format = ? AND car_ordinal = ? AND performance_class = ?
                """, (rs, rowNum) -> new Entry(rs.getString("game_format"), rs.getInt("car_ordinal"), PerformanceClass.valueOf(rs.getString("performance_class")),
                rs.getInt("gear_count"), rs.getString("setup_values"), rs.getString("ranges"),
                rs.getObject("confirmed_at", OffsetDateTime.class).toInstant()), gameFormat, carOrdinal, performanceClass.name()).stream().findFirst();
    }

    public void upsert(final Entry entry) {
        jdbcTemplate.update("""
                INSERT INTO forza.car_setups (game_format, car_ordinal, performance_class, gear_count, setup_values, ranges, confirmed_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                ON CONFLICT (game_format, car_ordinal, performance_class)
                DO UPDATE SET gear_count = EXCLUDED.gear_count, setup_values = EXCLUDED.setup_values, ranges = EXCLUDED.ranges, confirmed_at = EXCLUDED.confirmed_at
                """, entry.gameFormat(), entry.carOrdinal(), entry.performanceClass().name(), entry.gearCount(), entry.valuesJson(), entry.rangesJson(),
                OffsetDateTime.ofInstant(entry.confirmedAt(), ZoneOffset.UTC));
    }

    public boolean delete(final String gameFormat, final int carOrdinal, final PerformanceClass performanceClass) {
        return jdbcTemplate.update("DELETE FROM forza.car_setups WHERE game_format = ? AND car_ordinal = ? AND performance_class = ?",
                gameFormat, carOrdinal, performanceClass.name()) > 0;
    }
}
