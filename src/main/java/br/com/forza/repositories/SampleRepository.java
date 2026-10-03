package br.com.forza.repositories;

import br.com.forza.models.entities.SampleRow;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Ingestão em lote da série temporal. Rodas vão em colunas {@code REAL[]} (ordem FL, FR, RL,
 * RR) pra manter a tabela estreita. Use {@code reWriteBatchedInserts=true} na URL JDBC.
 */
@Repository
public class SampleRepository {

    private static final String COLUMNS = """
            t_ms, lap_number, rpm, speed, power, torque, boost, gear, accel, brake, steer, accel_x, accel_z,
            pos_x, pos_z, on_rumble, susp, slip_ratio, slip_angle, combined_slip, tire_temp, tire_wear
            """;

    private static final String INSERT = """
            INSERT INTO forza.samples (session_id, %s)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (session_id, t_ms) DO NOTHING
            """.formatted(COLUMNS);

    private static final RowMapper<SampleRow> MAPPER = (rs, rowNum) -> new SampleRow(
            rs.getInt("t_ms"),
            rs.getInt("lap_number"),
            rs.getFloat("rpm"),
            rs.getFloat("speed"),
            rs.getFloat("power"),
            rs.getFloat("torque"),
            rs.getFloat("boost"),
            rs.getInt("gear"),
            rs.getInt("accel"),
            rs.getInt("brake"),
            rs.getInt("steer"),
            rs.getFloat("accel_x"),
            rs.getFloat("accel_z"),
            rs.getFloat("pos_x"),
            rs.getFloat("pos_z"),
            rs.getBoolean("on_rumble"),
            toFloats(rs.getArray("susp")),
            toFloats(rs.getArray("slip_ratio")),
            toFloats(rs.getArray("slip_angle")),
            toFloats(rs.getArray("combined_slip")),
            toFloats(rs.getArray("tire_temp")),
            toFloats(rs.getArray("tire_wear")));

    private final JdbcTemplate jdbcTemplate;

    public SampleRepository(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void batchInsert(final UUID sessionId, final List<SampleRow> rows) {
        jdbcTemplate.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(final PreparedStatement ps, final int i) throws SQLException {
                final SampleRow r = rows.get(i);
                ps.setObject(1, sessionId);
                ps.setInt(2, r.tMs());
                ps.setInt(3, r.lapNumber());
                ps.setFloat(4, r.rpm());
                ps.setFloat(5, r.speed());
                ps.setFloat(6, r.power());
                ps.setFloat(7, r.torque());
                ps.setFloat(8, r.boost());
                ps.setInt(9, r.gear());
                ps.setInt(10, r.accel());
                ps.setInt(11, r.brake());
                ps.setInt(12, r.steer());
                ps.setFloat(13, r.accelX());
                ps.setFloat(14, r.accelZ());
                ps.setFloat(15, r.posX());
                ps.setFloat(16, r.posZ());
                ps.setBoolean(17, r.onRumble());
                setFloats(ps, 18, r.susp());
                setFloats(ps, 19, r.slipRatio());
                setFloats(ps, 20, r.slipAngle());
                setFloats(ps, 21, r.combinedSlip());
                setFloats(ps, 22, r.tireTemp());
                setFloats(ps, 23, r.tireWear());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    /** Todas as amostras da sessão em ordem temporal — usado no cálculo do resumo. */
    public List<SampleRow> findAll(final UUID sessionId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM forza.samples WHERE session_id = ? ORDER BY t_ms", MAPPER, sessionId);
    }

    /** Instante (ms) da última amostra da sessão, ou -1 se não há amostras — a sessão retomada continua a partir dele. */
    public int maxTMs(final UUID sessionId) {
        final Integer max = jdbcTemplate.queryForObject("SELECT MAX(t_ms) FROM forza.samples WHERE session_id = ?", Integer.class, sessionId);
        return max == null ? -1 : max;
    }

    public List<SampleRow> findRange(final UUID sessionId, final int fromMs, final Integer toMs, final int limit) {
        final int upper = toMs == null ? Integer.MAX_VALUE : toMs;
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM forza.samples WHERE session_id = ? AND t_ms >= ? AND t_ms <= ? ORDER BY t_ms LIMIT ?",
                MAPPER, sessionId, fromMs, upper, limit);
    }

    private static void setFloats(final PreparedStatement ps, final int index, final float[] values) throws SQLException {
        if (values == null) {
            ps.setNull(index, Types.ARRAY);
            return;
        }
        final Float[] boxed = new Float[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        ps.setArray(index, ps.getConnection().createArrayOf("float4", boxed));
    }

    private static float[] toFloats(final Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        final Object[] boxed = (Object[]) array.getArray();
        final float[] values = new float[boxed.length];
        for (int i = 0; i < boxed.length; i++) {
            values[i] = ((Number) boxed[i]).floatValue();
        }
        return values;
    }
}
