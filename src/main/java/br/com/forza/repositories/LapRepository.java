package br.com.forza.repositories;

import br.com.forza.models.entities.LapRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LapRepository {

    private final JdbcTemplate jdbcTemplate;

    public LapRepository(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(final UUID sessionId, final int lapNumber, final float lapTimeS) {
        jdbcTemplate.update("""
                INSERT INTO forza.laps (session_id, lap_number, lap_time_s) VALUES (?, ?, ?)
                ON CONFLICT (session_id, lap_number) DO UPDATE SET lap_time_s = EXCLUDED.lap_time_s
                """, sessionId, lapNumber, lapTimeS);
    }

    public List<LapRecord> findBySession(final UUID sessionId) {
        return jdbcTemplate.query(
                "SELECT lap_number, lap_time_s FROM forza.laps WHERE session_id = ? ORDER BY lap_number",
                (rs, rowNum) -> new LapRecord(rs.getInt("lap_number"), rs.getFloat("lap_time_s")),
                sessionId);
    }
}
