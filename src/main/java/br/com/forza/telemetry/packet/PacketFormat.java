package br.com.forza.telemetry.packet;

import java.util.Optional;

/**
 * Formatos do pacote UDP "Data Out" do Forza, identificados pelo tamanho em bytes.
 * {@code dashOffset} é onde começa o bloco Dash (posição, velocidade, pneus, inputs…);
 * {@code -1} quando o formato não tem esse bloco (Sled).
 * <p>
 * Formato Horizon (FH4/FH5/FH6): 324 bytes, Dash a partir do offset 244 — no FH6 os 12
 * bytes entre 232 e 243 são {@code CarGroup}/{@code SmashableVelDiff}/{@code SmashableMass}.
 * Forza Motorsport 2023 Dash: 331 bytes, Dash a partir do 232, mais {@code TireWear} (311)
 * e {@code TrackOrdinal} (327).
 */
public enum PacketFormat {
    SLED("Sled", 232, -1, false),
    FM7_DASH("FM7-Dash", 311, 232, false),
    HORIZON("FH4/FH5/FH6", 324, 244, false),
    FM_DASH("FM2023-Dash", 331, 232, true);

    private final String label;
    private final int size;
    private final int dashOffset;
    private final boolean tireWear;

    PacketFormat(final String label, final int size, final int dashOffset, final boolean tireWear) {
        this.label = label;
        this.size = size;
        this.dashOffset = dashOffset;
        this.tireWear = tireWear;
    }

    public String label() {
        return label;
    }

    public int size() {
        return size;
    }

    public int dashOffset() {
        return dashOffset;
    }

    public boolean hasDash() {
        return dashOffset >= 0;
    }

    /** {@code TireWear} e {@code TrackOrdinal} só existem no Forza Motorsport 2023 Dash. */
    public boolean hasTireWear() {
        return tireWear;
    }

    /** Pelo rótulo ({@link #label()}), como gravado em {@code sessions.game_format}. */
    public static Optional<PacketFormat> fromLabel(final String label) {
        for (final PacketFormat format : values()) {
            if (format.label.equals(label)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    public static Optional<PacketFormat> fromSize(final int size) {
        for (final PacketFormat format : values()) {
            if (format.size == size) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
