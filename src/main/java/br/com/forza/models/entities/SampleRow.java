package br.com.forza.models.entities;

/**
 * Linha de {@code forza.samples} (~20 Hz). Rodas na ordem FL, FR, RL, RR. {@code accelX} é
 * lateral e {@code accelZ} longitudinal (espaço local do carro); {@code speed} em m/s,
 * {@code tireTemp} em °F; {@code tireWear} é {@code null} fora do Forza Motorsport.
 */
public record SampleRow(int tMs,
                        int lapNumber,
                        float rpm,
                        float speed,
                        float power,
                        float torque,
                        float boost,
                        int gear,
                        int accel,
                        int brake,
                        int steer,
                        float accelX,
                        float accelZ,
                        float posX,
                        float posZ,
                        boolean onRumble,
                        float[] susp,
                        float[] slipRatio,
                        float[] slipAngle,
                        float[] combinedSlip,
                        float[] tireTemp,
                        float[] tireWear) {
}
