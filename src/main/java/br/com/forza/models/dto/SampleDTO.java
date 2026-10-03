package br.com.forza.models.dto;

import br.com.forza.models.entities.SampleRow;

/**
 * Amostra da série temporal (~20 Hz). Rodas na ordem [FL, FR, RL, RR]. {@code speed} em m/s,
 * {@code tireTemp} em °F, {@code accelX} lateral e {@code accelZ} longitudinal (espaço local
 * do carro), {@code steer} de -127 (esquerda) a 127 (direita), {@code accel}/{@code brake} 0-255.
 * {@code tireWear} só vem no Forza Motorsport.
 */
public record SampleDTO(int tMs,
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
                        float[] suspension,
                        float[] slipRatio,
                        float[] slipAngle,
                        float[] combinedSlip,
                        float[] tireTemp,
                        float[] tireWear) {

    public static SampleDTO from(final SampleRow r) {
        return new SampleDTO(r.tMs(), r.lapNumber(), r.rpm(), r.speed(), r.power(), r.torque(), r.boost(),
                r.gear(), r.accel(), r.brake(), r.steer(), r.accelX(), r.accelZ(), r.posX(), r.posZ(), r.onRumble(),
                r.susp(), r.slipRatio(), r.slipAngle(), r.combinedSlip(), r.tireTemp(), r.tireWear());
    }
}
