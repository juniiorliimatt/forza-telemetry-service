package br.com.forza.services;

import static br.com.forza.support.Fixtures.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.forza.exceptions.InvalidCursorException;
import br.com.forza.exceptions.ResourceNotFoundException;
import br.com.forza.models.entities.LapRecord;
import br.com.forza.models.entities.SessionMeta;
import br.com.forza.repositories.LapRepository;
import br.com.forza.repositories.SampleRepository;
import br.com.forza.repositories.SessionRepository;
import br.com.forza.support.Fixtures;
import br.com.forza.telemetry.summary.SummaryCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class SessionQueryServiceTest {

    private final SessionRepository sessionRepository = mock(SessionRepository.class);
    private final LapRepository lapRepository = mock(LapRepository.class);
    private final SampleRepository sampleRepository = mock(SampleRepository.class);
    private final SessionQueryService service = new SessionQueryService(sessionRepository, lapRepository,
            sampleRepository, new SummaryCalculator(), new ObjectMapper());

    private static SessionMeta sessionAt(final Instant startedAt) {
        final var base = Fixtures.session(UUID.randomUUID(), 1, null);
        return new SessionMeta(base.id(), base.gameFormat(), base.carOrdinal(), base.carClass(), base.performanceIndex(),
                base.drivetrain(), base.cylinders(), base.engineMaxRpm(), base.engineIdleRpm(), null, startedAt,
                startedAt.plusSeconds(60), 100, null);
    }

    private static String cursorOf(final String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void list_withoutCursor_queriesFirstPageAskingOneExtraRow() {
        when(sessionRepository.findPage(any(), any(), anyInt())).thenReturn(List.of());

        final var page = service.list(null, 20);

        verify(sessionRepository).findPage(isNull(), isNull(), eq(21));
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void list_blankCursor_isTreatedAsFirstPage() {
        when(sessionRepository.findPage(any(), any(), anyInt())).thenReturn(List.of());

        service.list("   ", 5);

        verify(sessionRepository).findPage(isNull(), isNull(), eq(6));
    }

    @ParameterizedTest(name = "size pedido {0} -> limite consultado {1}")
    @ValueSource(ints = {0, -5})
    void list_sizeBelowOne_isClampedToOne(final int requested) {
        when(sessionRepository.findPage(any(), any(), anyInt())).thenReturn(List.of());

        service.list(null, requested);

        verify(sessionRepository).findPage(isNull(), isNull(), eq(2));
    }

    @Test
    void list_sizeAboveMax_isClampedTo100() {
        when(sessionRepository.findPage(any(), any(), anyInt())).thenReturn(List.of());

        service.list(null, 5000);

        verify(sessionRepository).findPage(isNull(), isNull(), eq(101));
    }

    @Test
    void list_moreRowsThanSize_returnsPageAndCursorOfLastItem() {
        final var first = sessionAt(Instant.parse("2026-10-03T12:00:03.123456Z"));
        final var second = sessionAt(Instant.parse("2026-10-03T12:00:02Z"));
        final var extra = sessionAt(Instant.parse("2026-10-03T12:00:01Z"));
        when(sessionRepository.findPage(any(), any(), eq(3))).thenReturn(List.of(first, second, extra));

        final var page = service.list(null, 2);

        assertThat(page.items()).extracting("id").containsExactly(first.id(), second.id());
        assertThat(page.nextCursor()).isNotNull();

        clearInvocations(sessionRepository);
        when(sessionRepository.findPage(any(), any(), eq(3))).thenReturn(List.of());
        service.list(page.nextCursor(), 2);
        final var startedAt = ArgumentCaptor.forClass(Instant.class);
        final var id = ArgumentCaptor.forClass(UUID.class);
        verify(sessionRepository).findPage(startedAt.capture(), id.capture(), eq(3));
        assertThat(startedAt.getValue()).isEqualTo(second.startedAt());
        assertThat(id.getValue()).isEqualTo(second.id());
    }

    @Test
    void list_cursorKeepsMicrosecondPrecision() {
        final var precise = sessionAt(Instant.parse("2026-10-03T12:00:03.123456Z"));
        final var other = sessionAt(Instant.parse("2026-10-03T12:00:02Z"));
        when(sessionRepository.findPage(any(), any(), anyInt())).thenReturn(List.of(precise, other));
        final var cursor = service.list(null, 1).nextCursor();

        clearInvocations(sessionRepository);
        when(sessionRepository.findPage(any(), any(), anyInt())).thenReturn(List.of());
        service.list(cursor, 1);

        final var startedAt = ArgumentCaptor.forClass(Instant.class);
        verify(sessionRepository).findPage(startedAt.capture(), any(), eq(2));
        assertThat(startedAt.getValue()).isEqualTo(Instant.parse("2026-10-03T12:00:03.123456Z"));
    }

    @Test
    void list_exactlySizeRows_hasNoNextCursor() {
        when(sessionRepository.findPage(any(), any(), eq(3)))
                .thenReturn(List.of(sessionAt(Instant.now()), sessionAt(Instant.now())));

        assertThat(service.list(null, 2).nextCursor()).isNull();
    }

    @ParameterizedTest(name = "cursor inválido: {0}")
    @ValueSource(strings = {"@@@nao-e-base64@@@", "c2VtLXBpcGU", "bm90LWEtbG9uZ3w1NTBlODQwMC1lMjliLTQxZDQtYTcxNi00NDY2NTU0NDAwMDA", "MTIzfG5vdC1hLXV1aWQ"})
    void list_malformedCursor_throwsInvalidCursor(final String cursor) {
        assertThatThrownBy(() -> service.list(cursor, 10)).isInstanceOf(InvalidCursorException.class);

        verify(sessionRepository, never()).findPage(any(), any(), anyInt());
    }

    @Test
    void list_cursorWithValidShape_isAccepted() {
        when(sessionRepository.findPage(any(), any(), anyInt())).thenReturn(List.of());
        final var cursor = cursorOf("1759492800000000|" + UUID.randomUUID());

        assertThat(service.list(cursor, 10).items()).isEmpty();
    }

    @Test
    void get_unknownId_throwsNotFound() {
        final var id = UUID.randomUUID();
        when(sessionRepository.findById(id)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void get_knownId_mapsToDto() {
        final var meta = sessionAt(Instant.parse("2026-10-03T12:00:00Z"));
        when(sessionRepository.findById(meta.id())).thenReturn(java.util.Optional.of(meta));

        final var dto = service.get(meta.id());

        assertThat(dto.id()).isEqualTo(meta.id());
        assertThat(dto.drivetrain()).isEqualTo("RWD");
        assertThat(dto.active()).isFalse();
    }

    @Test
    void laps_unknownSession_throwsNotFoundWithoutQueryingLaps() {
        final var id = UUID.randomUUID();
        when(sessionRepository.findById(id)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.laps(id)).isInstanceOf(ResourceNotFoundException.class);

        verify(lapRepository, never()).findBySession(any());
    }

    @Test
    void laps_knownSession_returnsLapTimes() {
        final var meta = sessionAt(Instant.now());
        when(sessionRepository.findById(meta.id())).thenReturn(java.util.Optional.of(meta));
        when(lapRepository.findBySession(meta.id())).thenReturn(List.of(new LapRecord(1, 62.5f), new LapRecord(2, 61.25f)));

        assertThat(service.laps(meta.id())).extracting("lapNumber", "lapTimeS")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 62.5f), org.assertj.core.groups.Tuple.tuple(2, 61.25f));
    }

    @Test
    void summary_storedJson_isReturnedWithoutRecalculating() {
        final var id = UUID.randomUUID();
        when(sessionRepository.findById(id)).thenReturn(java.util.Optional.of(
                Fixtures.session(id, 1, "{\"samples\":321,\"durationS\":16.0}")));

        final var summary = service.summary(id);

        assertThat(summary).containsEntry("samples", 321).containsEntry("durationS", 16.0);
        verify(sampleRepository, never()).findAll(any());
    }

    @Test
    void summary_activeSession_isCalculatedOnTheFly() {
        final var id = UUID.randomUUID();
        when(sessionRepository.findById(id)).thenReturn(java.util.Optional.of(Fixtures.session(id, 1, null)));
        final var rows = new ArrayList<>(List.of(sample().tMs(0).build(), sample().tMs(2000).build()));
        when(sampleRepository.findAll(id)).thenReturn(rows);
        when(lapRepository.findBySession(id)).thenReturn(List.of());

        final var summary = service.summary(id);

        assertThat(summary).containsEntry("samples", 2).containsEntry("durationS", 2.0);
    }

    @Test
    void summary_corruptStoredJson_fallsBackToRecalculation() {
        final var id = UUID.randomUUID();
        when(sessionRepository.findById(id)).thenReturn(java.util.Optional.of(Fixtures.session(id, 1, "{isto não é json")));
        when(sampleRepository.findAll(id)).thenReturn(List.of(sample().tMs(500).build()));
        when(lapRepository.findBySession(id)).thenReturn(List.of());

        assertThat(service.summary(id)).containsEntry("samples", 1);
    }

    @Test
    void summary_unknownSession_throwsNotFound() {
        final var id = UUID.randomUUID();
        when(sessionRepository.findById(id)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.summary(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void samples_clampsLimitAndNegativeFrom() {
        final var meta = sessionAt(Instant.now());
        when(sessionRepository.findById(meta.id())).thenReturn(java.util.Optional.of(meta));
        when(sampleRepository.findRange(any(), anyInt(), any(), anyInt())).thenReturn(List.of());

        service.samples(meta.id(), -50, 9000, 999_999);

        verify(sampleRepository).findRange(meta.id(), 0, 9000, 10_000);
    }

    @Test
    void samples_limitBelowOne_isClampedToOne() {
        final var meta = sessionAt(Instant.now());
        when(sessionRepository.findById(meta.id())).thenReturn(java.util.Optional.of(meta));
        when(sampleRepository.findRange(any(), anyInt(), any(), anyInt())).thenReturn(List.of());

        service.samples(meta.id(), 100, null, 0);

        verify(sampleRepository).findRange(meta.id(), 100, null, 1);
    }

    @Test
    void samples_mapsRowsToDtos() {
        final var meta = sessionAt(Instant.now());
        when(sessionRepository.findById(meta.id())).thenReturn(java.util.Optional.of(meta));
        when(sampleRepository.findRange(any(), anyInt(), any(), anyInt())).thenReturn(List.of(sample().tMs(50).gear(4).build()));

        assertThat(service.samples(meta.id(), 0, null, 10)).singleElement()
                .satisfies(s -> {
                    assertThat(s.tMs()).isEqualTo(50);
                    assertThat(s.gear()).isEqualTo(4);
                });
    }

    @Test
    void samples_unknownSession_throwsNotFound() {
        final var id = UUID.randomUUID();
        when(sessionRepository.findById(id)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.samples(id, 0, null, 10)).isInstanceOf(ResourceNotFoundException.class);
    }
}
