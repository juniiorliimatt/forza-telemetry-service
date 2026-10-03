package br.com.forza.models;

import static br.com.forza.support.Fixtures.sample;
import static br.com.forza.support.Fixtures.session;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.forza.models.dto.TuningSummaryDTO;
import br.com.forza.models.entities.LapRecord;
import br.com.forza.telemetry.summary.SummaryCalculator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Garante que {@link TuningSummaryDTO} (o schema publicado no OpenAPI) descreve exatamente o
 * que o {@link SummaryCalculator} produz: campo novo/renomeado no cálculo sem acompanhar o
 * DTO quebra aqui (FAIL_ON_UNKNOWN_PROPERTIES), em vez de o contrato mentir em silêncio.
 */
class TuningSummaryDTOTest {

    private final ObjectMapper strict = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final SummaryCalculator calculator = new SummaryCalculator();

    private TuningSummaryDTO roundTrip(final java.util.Map<String, Object> summary) throws Exception {
        return strict.readValue(strict.writeValueAsString(summary), TuningSummaryDTO.class);
    }

    @Test
    void fullSummary_deserializesIntoTheDtoWithoutUnknownFields() throws Exception {
        final var rows = List.of(
                sample().tMs(0).steer(50).brake(100).slipAngle(0.8f, 0.8f, 0.2f, 0.2f).power(200_000f).tireWear(0.1f, 0.2f, 0.3f, 0.4f).build(),
                sample().tMs(1000).accel(255).gear(2).slipRatio(0f, 0f, 1.5f, 0f).onRumble(true).build(),
                sample().tMs(2000).brake(200).slipRatio(1.5f, 0f, 0f, 0f).build());

        final var dto = roundTrip(calculator.calculate(session(1), rows, List.of(new LapRecord(1, 62.5f))));

        assertThat(dto.samples()).isEqualTo(3);
        assertThat(dto.durationS()).isEqualTo(2.0);
        assertThat(dto.suspension()).containsKeys("FL", "FR", "RL", "RR");
        assertThat(dto.speedKmh().max()).isEqualTo(72.0);
        assertThat(dto.engine().gears()).isNotEmpty();
        assertThat(dto.tires().get("FL").wearFinal()).isNull();
        assertThat(dto.cornerBalance().entry().samples()).isEqualTo(1);
        assertThat(dto.cornerBalance().entry().understeerPct()).isEqualTo(100.0);
        assertThat(dto.cornerBalance().mid().frontSlipAngleMean()).isNull();
        assertThat(dto.braking().samples()).isEqualTo(1);
        assertThat(dto.traction().spinPctByGear()).containsKey("2");
        assertThat(dto.laps().bestS()).isEqualTo(62.5);
        assertThat(dto.laps().times()).singleElement().satisfies(l -> assertThat(l.lap()).isEqualTo(1));
        assertThat(dto.onRumbleStripPct()).isEqualTo(33.3);
    }

    @Test
    void summaryWithTireWearOnLastSample_exposesWearFinal() throws Exception {
        final var rows = List.of(sample().tMs(0).tireWear(0.1234f, 0.2f, 0.3f, 0.4f).build());

        final var dto = roundTrip(calculator.calculate(session(1), rows, List.of()));

        assertThat(dto.tires().get("FL").wearFinal()).isEqualTo(0.123);
        assertThat(dto.laps().bestS()).isNull();
    }

    @Test
    void emptySession_hasOnlyCountAndDuration() throws Exception {
        final var dto = roundTrip(calculator.calculate(session(1), List.of(), List.of()));

        assertThat(dto.samples()).isZero();
        assertThat(dto.durationS()).isZero();
        assertThat(dto.suspension()).isNull();
        assertThat(dto.engine()).isNull();
    }
}
