package br.com.forza.models;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.forza.models.dto.SessionDTO;
import br.com.forza.models.entities.SessionMeta;
import br.com.forza.support.Fixtures;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class SessionDTOTest {

    @ParameterizedTest
    @CsvSource({"0,FWD", "1,RWD", "2,AWD", "3,UNKNOWN", "-1,UNKNOWN"})
    void from_mapsDrivetrainCodeToName(final int code, final String expected) {
        assertThat(SessionDTO.from(Fixtures.session(code)).drivetrain()).isEqualTo(expected);
    }

    @Test
    void from_sessionWithoutEnd_isActive() {
        assertThat(SessionDTO.from(Fixtures.session(0)).active()).isTrue();
    }

    @Test
    void from_sessionWithEnd_isNotActive() {
        final var base = Fixtures.session(UUID.randomUUID(), 0, null);
        final var ended = new SessionMeta(base.id(), base.gameFormat(), 1, 1, 1, 0, 4, 1f, 1f, 7,
                base.startedAt(), Instant.parse("2026-10-03T12:05:00Z"), 100, null);

        final var dto = SessionDTO.from(ended);

        assertThat(dto.active()).isFalse();
        assertThat(dto.trackOrdinal()).isEqualTo(7);
        assertThat(dto.sampleCount()).isEqualTo(100);
    }

    @Test
    void from_sessionWithSamplesPurged_flagsIt() {
        final var base = Fixtures.session(UUID.randomUUID(), 0, null);
        final var purged = new SessionMeta(base.id(), base.gameFormat(), 1, 1, 1, 0, 4, 1f, 1f, 7,
                base.startedAt(), Instant.parse("2026-10-03T12:05:00Z"), 100, null, Instant.parse("2026-10-10T12:00:00Z"));

        assertThat(SessionDTO.from(purged).samplesPurged()).isTrue();
        assertThat(SessionDTO.from(base).samplesPurged()).isFalse();
    }

    @Test
    void from_withoutCatalogName_hasNullCarName() {
        assertThat(SessionDTO.from(Fixtures.session(0)).carName()).isNull();
    }

    @Test
    void from_withCatalogName_exposesCarName() {
        assertThat(SessionDTO.from(Fixtures.session(0), "2021 Porsche 911 GT3").carName()).isEqualTo("2021 Porsche 911 GT3");
    }
}
