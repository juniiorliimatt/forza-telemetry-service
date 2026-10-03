package br.com.forza.telemetry.packet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * Decodifica o pacote Data Out (little-endian) pelos offsets documentados em
 * support.forza.net. O formato é detectado só pelo tamanho do datagrama; qualquer outro
 * tamanho devolve {@link Optional#empty()}.
 */
public final class PacketParser {

    private static final int TIRE_WEAR_OFFSET = 311;
    private static final int TRACK_ORDINAL_OFFSET = 327;

    private PacketParser() {
    }

    public static Optional<TelemetryPacket> parse(final byte[] data) {
        final Optional<PacketFormat> found = PacketFormat.fromSize(data.length);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        final PacketFormat format = found.get();
        final ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        final TelemetryPacket.Dash dash = format.hasDash() ? parseDash(buf, format) : null;

        return Optional.of(new TelemetryPacket(
                format,
                buf.getInt(0) != 0,
                buf.getInt(4) & 0xFFFFFFFFL,
                buf.getFloat(8),
                buf.getFloat(12),
                buf.getFloat(16),
                buf.getFloat(20),
                buf.getFloat(24),
                buf.getFloat(28),
                wheels(buf, 68),
                wheels(buf, 84),
                wheels(buf, 164),
                wheels(buf, 180),
                anyOnRumbleStrip(buf),
                buf.getInt(212),
                buf.getInt(216),
                buf.getInt(220),
                buf.getInt(224),
                buf.getInt(228),
                dash));
    }

    private static TelemetryPacket.Dash parseDash(final ByteBuffer buf, final PacketFormat format) {
        final int d = format.dashOffset();
        return new TelemetryPacket.Dash(
                buf.getFloat(d),
                buf.getFloat(d + 4),
                buf.getFloat(d + 8),
                buf.getFloat(d + 12),
                buf.getFloat(d + 16),
                buf.getFloat(d + 20),
                wheels(buf, d + 24),
                buf.getFloat(d + 40),
                buf.getFloat(d + 44),
                buf.getFloat(d + 48),
                buf.getFloat(d + 52),
                buf.getFloat(d + 56),
                buf.getFloat(d + 60),
                buf.getFloat(d + 64),
                buf.getShort(d + 68) & 0xFFFF,
                buf.get(d + 70) & 0xFF,
                buf.get(d + 71) & 0xFF,
                buf.get(d + 72) & 0xFF,
                buf.get(d + 73) & 0xFF,
                buf.get(d + 74) & 0xFF,
                buf.get(d + 75) & 0xFF,
                buf.get(d + 76),
                format.hasTireWear() ? wheels(buf, TIRE_WEAR_OFFSET) : null,
                format.hasTireWear() ? buf.getInt(TRACK_ORDINAL_OFFSET) : null);
    }

    private static float[] wheels(final ByteBuffer buf, final int offset) {
        return new float[]{
                buf.getFloat(offset),
                buf.getFloat(offset + 4),
                buf.getFloat(offset + 8),
                buf.getFloat(offset + 12)};
    }

    /** WheelOnRumbleStrip (4 × s32 a partir do offset 116): true se qualquer roda estiver na zebra. */
    private static boolean anyOnRumbleStrip(final ByteBuffer buf) {
        for (int wheel = 0; wheel < 4; wheel++) {
            if (buf.getInt(116 + wheel * 4) != 0) {
                return true;
            }
        }
        return false;
    }
}
