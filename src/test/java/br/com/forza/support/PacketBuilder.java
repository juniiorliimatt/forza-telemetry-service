package br.com.forza.support;

import br.com.forza.telemetry.packet.PacketFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Monta pacotes Data Out sintéticos (little-endian) nos offsets documentados do Forza.
 * Só os campos setados ficam diferentes de zero.
 */
public final class PacketBuilder {

    private static final int TIRE_WEAR_OFFSET = 311;
    private static final int TRACK_ORDINAL_OFFSET = 327;

    private final PacketFormat format;
    private final ByteBuffer buf;

    private PacketBuilder(final PacketFormat format) {
        this.format = format;
        this.buf = ByteBuffer.allocate(format.size()).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static PacketBuilder of(final PacketFormat format) {
        return new PacketBuilder(format);
    }

    /** Pacote Dash em corrida, com carro/PI/tração básicos — ponto de partida dos testes. */
    public static PacketBuilder racing(final PacketFormat format) {
        return of(format).raceOn(true).car(1234, 5, 800, 1, 8).engine(8000f, 1000f).rpm(4000f);
    }

    public PacketBuilder raceOn(final boolean on) {
        buf.putInt(0, on ? 1 : 0);
        return this;
    }

    public PacketBuilder timestampMs(final long value) {
        buf.putInt(4, (int) value);
        return this;
    }

    public PacketBuilder engine(final float maxRpm, final float idleRpm) {
        buf.putFloat(8, maxRpm);
        buf.putFloat(12, idleRpm);
        return this;
    }

    public PacketBuilder rpm(final float value) {
        buf.putFloat(16, value);
        return this;
    }

    public PacketBuilder acceleration(final float x, final float y, final float z) {
        buf.putFloat(20, x);
        buf.putFloat(24, y);
        buf.putFloat(28, z);
        return this;
    }

    public PacketBuilder suspension(final float... fourWheels) {
        return wheels(68, fourWheels);
    }

    public PacketBuilder slipRatio(final float... fourWheels) {
        return wheels(84, fourWheels);
    }

    public PacketBuilder slipAngle(final float... fourWheels) {
        return wheels(164, fourWheels);
    }

    public PacketBuilder combinedSlip(final float... fourWheels) {
        return wheels(180, fourWheels);
    }

    public PacketBuilder rumble(final int wheel, final boolean on) {
        buf.putInt(116 + wheel * 4, on ? 1 : 0);
        return this;
    }

    public PacketBuilder car(final int ordinal, final int carClass, final int performanceIndex, final int drivetrain,
                             final int cylinders) {
        buf.putInt(212, ordinal);
        buf.putInt(216, carClass);
        buf.putInt(220, performanceIndex);
        buf.putInt(224, drivetrain);
        buf.putInt(228, cylinders);
        return this;
    }

    public PacketBuilder position(final float x, final float y, final float z) {
        final int d = dash();
        buf.putFloat(d, x);
        buf.putFloat(d + 4, y);
        buf.putFloat(d + 8, z);
        return this;
    }

    public PacketBuilder speed(final float metersPerSecond) {
        buf.putFloat(dash() + 12, metersPerSecond);
        return this;
    }

    public PacketBuilder power(final float watts) {
        buf.putFloat(dash() + 16, watts);
        return this;
    }

    public PacketBuilder torque(final float newtonMeters) {
        buf.putFloat(dash() + 20, newtonMeters);
        return this;
    }

    public PacketBuilder tireTemp(final float... fourWheels) {
        return wheels(dash() + 24, fourWheels);
    }

    public PacketBuilder boost(final float value) {
        buf.putFloat(dash() + 40, value);
        return this;
    }

    public PacketBuilder fuel(final float value) {
        buf.putFloat(dash() + 44, value);
        return this;
    }

    public PacketBuilder lapTimes(final float best, final float last, final float current) {
        final int d = dash();
        buf.putFloat(d + 52, best);
        buf.putFloat(d + 56, last);
        buf.putFloat(d + 60, current);
        return this;
    }

    public PacketBuilder lapNumber(final int lap) {
        buf.putShort(dash() + 68, (short) lap);
        return this;
    }

    public PacketBuilder racePosition(final int position) {
        buf.put(dash() + 70, (byte) position);
        return this;
    }

    /** Pedais 0-255, marcha e esterço (-127..127, com sinal). */
    public PacketBuilder inputs(final int accel, final int brake, final int clutch, final int handbrake, final int gear,
                                final int steer) {
        final int d = dash();
        buf.put(d + 71, (byte) accel);
        buf.put(d + 72, (byte) brake);
        buf.put(d + 73, (byte) clutch);
        buf.put(d + 74, (byte) handbrake);
        buf.put(d + 75, (byte) gear);
        buf.put(d + 76, (byte) steer);
        return this;
    }

    /** Só existe no Forza Motorsport 2023 (Dash 331 bytes). */
    public PacketBuilder tireWear(final float... fourWheels) {
        requireTireWear();
        return wheels(TIRE_WEAR_OFFSET, fourWheels);
    }

    public PacketBuilder trackOrdinal(final int value) {
        requireTireWear();
        buf.putInt(TRACK_ORDINAL_OFFSET, value);
        return this;
    }

    private void requireTireWear() {
        if (!format.hasTireWear()) {
            throw new IllegalStateException("formato " + format.label() + " não tem TireWear/TrackOrdinal");
        }
    }

    private PacketBuilder wheels(final int offset, final float... values) {
        if (values.length != 4) {
            throw new IllegalArgumentException("esperadas 4 rodas (FL, FR, RL, RR)");
        }
        for (int i = 0; i < 4; i++) {
            buf.putFloat(offset + i * 4, values[i]);
        }
        return this;
    }

    /**
     * Offsets do bloco Dash fixados aqui (documentação oficial do Data Out), e não lidos de
     * {@link PacketFormat#dashOffset()} — senão o teste seria circular e não pegaria um offset
     * errado no enum.
     */
    private int dash() {
        return switch (format) {
            case HORIZON -> 244;
            case FM7_DASH, FM_DASH -> 232;
            case SLED -> throw new IllegalStateException("formato " + format.label() + " não tem bloco Dash");
        };
    }

    public byte[] build() {
        return buf.array().clone();
    }
}
