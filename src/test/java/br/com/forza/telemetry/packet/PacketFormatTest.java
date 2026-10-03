package br.com.forza.telemetry.packet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PacketFormatTest {

    @ParameterizedTest
    @CsvSource({"232,SLED", "311,FM7_DASH", "324,HORIZON", "331,FM_DASH"})
    void fromSize_knownSize_returnsFormat(final int size, final PacketFormat expected) {
        assertThat(PacketFormat.fromSize(size)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 231, 233, 310, 325, 330, 332, 1500})
    void fromSize_unknownSize_returnsEmpty(final int size) {
        assertThat(PacketFormat.fromSize(size)).isEmpty();
    }

    @Test
    void hasDash_onlyFalseForSled() {
        assertThat(PacketFormat.SLED.hasDash()).isFalse();
        assertThat(PacketFormat.FM7_DASH.hasDash()).isTrue();
        assertThat(PacketFormat.HORIZON.hasDash()).isTrue();
        assertThat(PacketFormat.FM_DASH.hasDash()).isTrue();
    }

    @Test
    void hasTireWear_onlyForForzaMotorsport2023() {
        assertThat(PacketFormat.FM_DASH.hasTireWear()).isTrue();
        assertThat(PacketFormat.FM7_DASH.hasTireWear()).isFalse();
        assertThat(PacketFormat.HORIZON.hasTireWear()).isFalse();
        assertThat(PacketFormat.SLED.hasTireWear()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"Sled,SLED", "FM7-Dash,FM7_DASH", "FH4/FH5/FH6,HORIZON", "FM2023-Dash,FM_DASH"})
    void fromLabel_knownLabel_returnsFormat(final String label, final PacketFormat expected) {
        assertThat(PacketFormat.fromLabel(label)).contains(expected);
    }

    @Test
    void fromLabel_unknownOrNull_returnsEmpty() {
        assertThat(PacketFormat.fromLabel("x")).isEmpty();
        assertThat(PacketFormat.fromLabel(null)).isEmpty();
    }
}
