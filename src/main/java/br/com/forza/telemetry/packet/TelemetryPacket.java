package br.com.forza.telemetry.packet;

/**
 * Pacote Data Out já decodificado. Rodas sempre na ordem FL, FR, RL, RR. {@code dash} é
 * {@code null} no formato Sled. Temperatura de pneu em °F, velocidade em m/s, potência em
 * W, torque em N·m (unidades do jogo).
 */
public record TelemetryPacket(PacketFormat format,
                              boolean raceOn,
                              long timestampMs,
                              float engineMaxRpm,
                              float engineIdleRpm,
                              float currentRpm,
                              float accelerationX,
                              float accelerationY,
                              float accelerationZ,
                              float[] suspension,
                              float[] slipRatio,
                              float[] slipAngle,
                              float[] combinedSlip,
                              boolean onRumble,
                              int carOrdinal,
                              int carClass,
                              int performanceIndex,
                              int drivetrain,
                              int cylinders,
                              Dash dash) {

    /** Bloco Dash. {@code tireWear} e {@code trackOrdinal} só vêm preenchidos no FM2023. */
    public record Dash(float positionX,
                       float positionY,
                       float positionZ,
                       float speed,
                       float power,
                       float torque,
                       float[] tireTemp,
                       float boost,
                       float fuel,
                       float distanceTraveled,
                       float bestLap,
                       float lastLap,
                       float currentLap,
                       float currentRaceTime,
                       int lapNumber,
                       int racePosition,
                       int accel,
                       int brake,
                       int clutch,
                       int handbrake,
                       int gear,
                       int steer,
                       float[] tireWear,
                       Integer trackOrdinal) {
    }
}
