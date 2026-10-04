package br.com.forza.tuning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.forza.config.TuningProperties;
import br.com.forza.repositories.SampleRepository;
import br.com.forza.repositories.SessionRepository;
import br.com.forza.telemetry.PerformanceClass;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SamplePurgerTest {

    private static final String HORIZON = "FH4/FH5/FH6";
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final SampleRepository samples = mock(SampleRepository.class);
    private final TuningProperties properties = new TuningProperties(10, 50_000, 20, 5000, 2);
    private final SamplePurger purger = new SamplePurger(sessions, samples, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void purge_deletesTheSamplesOfTheCandidatesAndMarksThem() {
        final var ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(sessions.findSamplePurgeCandidates(HORIZON, 3667, PerformanceClass.S2, 2)).thenReturn(ids);

        final int purged = purger.purge(HORIZON, 3667, PerformanceClass.S2);

        assertThat(purged).isEqualTo(3);
        final var order = inOrder(samples, sessions);
        order.verify(samples).deleteBySessions(ids);
        order.verify(sessions).markSamplesPurged(ids, NOW);
    }

    @Test
    void purge_keepsTheConfiguredNumberOfNewestSessions() {
        final var otherKeep = new SamplePurger(sessions, samples, new TuningProperties(10, 50_000, 20, 5000, 0), Clock.fixed(NOW, ZoneOffset.UTC));
        when(sessions.findSamplePurgeCandidates(HORIZON, 1, PerformanceClass.A, 0)).thenReturn(List.of(UUID.randomUUID()));

        assertThat(otherKeep.purge(HORIZON, 1, PerformanceClass.A)).isEqualTo(1);
        verify(sessions).findSamplePurgeCandidates(HORIZON, 1, PerformanceClass.A, 0);
    }

    @Test
    void purge_withNoCandidates_touchesNothing() {
        when(sessions.findSamplePurgeCandidates(HORIZON, 3667, PerformanceClass.S2, 2)).thenReturn(List.of());

        assertThat(purger.purge(HORIZON, 3667, PerformanceClass.S2)).isZero();

        verify(samples, never()).deleteBySessions(any());
        verify(sessions, never()).markSamplesPurged(any(), any());
    }
}
