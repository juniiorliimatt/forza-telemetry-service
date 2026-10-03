package br.com.forza.telemetry.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import br.com.forza.config.TelemetryProperties;
import br.com.forza.models.entities.SampleRow;
import br.com.forza.models.entities.SessionMeta;
import br.com.forza.repositories.LapRepository;
import br.com.forza.repositories.SampleRepository;
import br.com.forza.repositories.SessionRepository;
import br.com.forza.support.PacketBuilder;
import br.com.forza.telemetry.packet.PacketFormat;
import br.com.forza.telemetry.summary.SummaryCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

/**
 * Exercita o worker pela thread real (start/offer/stop). {@code stop()} espera a fila
 * drenar e fecha a sessão ativa, então os testes são determinísticos sem sleeps — exceto o
 * de inatividade, que depende do relógio e usa {@code timeout} do Mockito.
 */
class IngestWorkerTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    private final SessionRepository sessionRepository = mock(SessionRepository.class);
    private final LapRepository lapRepository = mock(LapRepository.class);
    private final SampleRepository sampleRepository = mock(SampleRepository.class);
    private final LiveSnapshot liveSnapshot = new LiveSnapshot();
    private IngestWorker worker;

    @AfterEach
    void tearDown() {
        if (worker != null && worker.isRunning()) {
            worker.stop();
        }
    }

    private IngestWorker workerWith(final int queueCapacity, final int sampleEvery, final int minSamples,
                                    final Duration idleTimeout) {
        return workerWith(queueCapacity, sampleEvery, minSamples, idleTimeout, 5000);
    }

    private IngestWorker workerWith(final int queueCapacity, final int sampleEvery, final int minSamples,
                                    final Duration idleTimeout, final int maxSamples) {
        final var properties = new TelemetryProperties(
                new TelemetryProperties.Udp(true, 5310, queueCapacity, 65_536),
                sampleEvery, idleTimeout, maxSamples, Duration.ofMillis(50), 200, minSamples, Duration.ofSeconds(5));
        worker = new IngestWorker(properties, liveSnapshot, sessionRepository, lapRepository, sampleRepository,
                new SummaryCalculator(), new ObjectMapper());
        return worker;
    }

    private IngestWorker defaultWorker() {
        return workerWith(1000, 1, 1, Duration.ofSeconds(30));
    }

    private static RawPacket raw(final byte[] data, final long nanos) {
        return new RawPacket(data, nanos);
    }

    private static byte[] racing() {
        return PacketBuilder.racing(PacketFormat.HORIZON).speed(20f).build();
    }

    /** Copia as linhas no momento da chamada: o worker reaproveita (e limpa) a lista {@code pending} após o flush. */
    private final List<SampleRow> persisted = new ArrayList<>();

    private final Answer<Void> copyBatch = invocation -> {
        persisted.addAll(invocation.<List<SampleRow>>getArgument(1));
        return null;
    };

    @BeforeEach
    void stubBatchInsert() {
        doAnswer(copyBatch).when(sampleRepository).batchInsert(any(), any());
    }

    private List<SampleRow> persistedSamples() {
        return persisted;
    }

    @Test
    void run_racingPackets_openPersistAndCloseSessionWithSummary() {
        final var w = defaultWorker();
        for (int i = 0; i < 5; i++) {
            w.offer(raw(racing(), i * 16 * MS));
        }

        w.start();
        w.stop();

        final var meta = ArgumentCaptor.forClass(SessionMeta.class);
        verify(sessionRepository).insert(meta.capture());
        assertThat(meta.getValue().gameFormat()).isEqualTo("FH4/FH5/FH6");
        assertThat(meta.getValue().carOrdinal()).isEqualTo(1234);
        assertThat(meta.getValue().engineMaxRpm()).isEqualTo(8000f);
        assertThat(persistedSamples()).hasSize(5);
        final var summary = ArgumentCaptor.forClass(String.class);
        verify(sessionRepository).close(eq(meta.getValue().id()), any(), eq(5), summary.capture());
        assertThat(summary.getValue()).contains("\"samples\"");
        verify(sessionRepository, never()).delete(any());
    }

    private static byte[] parked() {
        return PacketBuilder.racing(PacketFormat.HORIZON).speed(0f).build();
    }

    private static long seconds(final int value) {
        return TimeUnit.SECONDS.toNanos(value);
    }

    /**
     * Na garagem o jogo continua mandando pacotes com IsRaceOn=1 e o carro parado. Só as amostras limitam a sessão,
     * então tempo parado não gera amostra (e portanto não conta para a janela nem para o tuning).
     */
    @Test
    void run_carStopped_generatesNoSamples() {
        final var w = defaultWorker();
        for (int i = 0; i < 3; i++) {
            w.offer(raw(racing(), i * 16 * MS));
        }
        for (int s = 1; s <= 5; s++) {
            w.offer(raw(parked(), seconds(s)));
        }
        for (int i = 0; i < 2; i++) {
            w.offer(raw(racing(), seconds(10) + i * 16 * MS));
        }

        w.start();
        w.stop();

        verify(sessionRepository, times(1)).insert(any());
        assertThat(persistedSamples()).hasSize(5);   // 3 + 2 andando; os 5 parados não entram
    }

    @Test
    void run_parkedPackets_doNotCountTowardTheRotationLimit() {
        final var w = rotatingWorker(3);
        for (int i = 0; i < 2; i++) {
            w.offer(raw(racing(), i * 16 * MS));
        }
        for (int s = 1; s <= 50; s++) {
            w.offer(raw(parked(), seconds(s)));      // 50 pacotes parado: não enchem a janela
        }
        w.offer(raw(racing(), seconds(60)));

        w.start();
        w.stop();

        verify(sessionRepository, times(1)).insert(any());
        verify(sessionRepository).close(any(), any(), eq(3), anyString());
    }

    @Test
    void run_parkedForAWhile_keepsTheSessionOpen_noTimeRuleClosesIt() {
        final var w = defaultWorker();
        for (int i = 0; i < 3; i++) {
            w.offer(raw(racing(), i * 16 * MS));
        }
        for (int m = 1; m <= 20; m++) {
            w.offer(raw(parked(), seconds(m * 60)));   // 20 minutos parado, pelo carimbo dos pacotes
        }

        w.start();
        w.stop();

        verify(sessionRepository, times(1)).insert(any());
        verify(sessionRepository).close(any(), any(), eq(3), anyString());
    }

    private IngestWorker rotatingWorker(final int maxSamples) {
        return workerWith(1000, 1, 1, Duration.ofSeconds(30), maxSamples);
    }

    private static byte[] freeRoam() {
        return PacketBuilder.racing(PacketFormat.HORIZON).speed(20f).lapNumber(0).build();
    }

    private static byte[] inRace() {
        return PacketBuilder.racing(PacketFormat.HORIZON).speed(40f).lapNumber(1).build();
    }

    /** O jogo não avisa quando o carro está na garagem: a sessão fecha sozinha ao juntar N amostras, e a próxima abre na sequência. */
    @Test
    void run_freeRoam_rotatesTheSessionEveryMaxSamples() {
        final var w = rotatingWorker(3);
        for (int i = 0; i < 7; i++) {
            w.offer(raw(freeRoam(), i * 16 * MS));
        }

        w.start();
        w.stop();

        verify(sessionRepository, times(3)).insert(any());
        final var closed = ArgumentCaptor.forClass(Integer.class);
        verify(sessionRepository, times(3)).close(any(), any(), closed.capture(), anyString());
        assertThat(closed.getAllValues()).containsExactly(3, 3, 1);
        assertThat(persistedSamples()).hasSize(7);
    }

    @Test
    void run_belowTheLimit_doesNotRotate() {
        final var w = rotatingWorker(3);
        for (int i = 0; i < 2; i++) {
            w.offer(raw(freeRoam(), i * 16 * MS));
        }

        w.start();
        w.stop();

        verify(sessionRepository, times(1)).insert(any());
    }

    @Test
    void run_midRace_waitsForTheRaceToEndBeforeRotating() {
        final var w = rotatingWorker(3);
        for (int i = 0; i < 7; i++) {
            w.offer(raw(inRace(), i * 16 * MS));      // 7 amostras numa corrida: passa do limite de 3 sem fechar
        }

        w.start();
        w.stop();

        verify(sessionRepository, times(1)).insert(any());
        verify(sessionRepository).close(any(), any(), eq(7), anyString());
    }

    @Test
    void run_whenTheRaceEnds_theSessionRotatesAtOnce() {
        final var w = rotatingWorker(3);
        for (int i = 0; i < 5; i++) {
            w.offer(raw(inRace(), i * 16 * MS));
        }
        for (int i = 5; i < 8; i++) {
            w.offer(raw(freeRoam(), i * 16 * MS));      // voltou ao mundo aberto (lapNumber 0)
        }

        w.start();
        w.stop();

        verify(sessionRepository, times(2)).insert(any());
        final var closed = ArgumentCaptor.forClass(Integer.class);
        verify(sessionRepository, times(2)).close(any(), any(), closed.capture(), anyString());
        assertThat(closed.getAllValues()).containsExactly(5, 3);   // a corrida inteira numa sessão; depois o mundo aberto
    }

    @Test
    void run_rotationCountsStoredSamples_notRawPackets() {
        final var w = workerWith(1000, 3, 1, Duration.ofSeconds(30), 3);
        for (int i = 0; i < 18; i++) {
            w.offer(raw(freeRoam(), i * 16 * MS));      // downsample 3: 1 amostra a cada 3 pacotes (o contador reinicia a cada sessão)
        }

        w.start();
        w.stop();

        final var closed = ArgumentCaptor.forClass(Integer.class);
        verify(sessionRepository, times(3)).close(any(), any(), closed.capture(), anyString());
        assertThat(closed.getAllValues()).containsExactly(3, 3, 2);   // 8 amostras gravadas de 18 pacotes — não 18 amostras
    }

    private static byte[] drivingWithPi(final int pi) {
        return PacketBuilder.racing(PacketFormat.HORIZON).speed(20f).car(1234, 3, pi, 1, 8).build();
    }

    /** Upgrade que muda a classe de PI é outra build do carro: a sessão fecha e a coleta do tuning não mistura as duas. */
    @Test
    void run_performanceClassChangeOnTheSameCar_closesTheSessionAndOpensAnother() {
        final var w = defaultWorker();
        for (int i = 0; i < 3; i++) {
            w.offer(raw(drivingWithPi(700), i * 16 * MS));          // A
        }
        for (int i = 3; i < 6; i++) {
            w.offer(raw(drivingWithPi(701), i * 16 * MS));          // S1
        }

        w.start();
        w.stop();

        final var metas = ArgumentCaptor.forClass(SessionMeta.class);
        verify(sessionRepository, times(2)).insert(metas.capture());
        assertThat(metas.getAllValues()).extracting(SessionMeta::performanceIndex).containsExactly(700, 701);
        verify(sessionRepository, times(2)).close(any(), any(), eq(3), anyString());
    }

    @Test
    void run_piChangeInsideTheSameClass_keepsTheSession() {
        final var w = defaultWorker();
        for (int i = 0; i < 3; i++) {
            w.offer(raw(drivingWithPi(650 + i * 10), i * 16 * MS));   // 650, 660, 670: sempre A
        }

        w.start();
        w.stop();

        verify(sessionRepository, times(1)).insert(any());
    }

    @Test
    void run_onlyStationaryPackets_neverOpenASession() {
        final var w = defaultWorker();
        for (int s = 0; s <= 30; s++) {
            w.offer(raw(parked(), seconds(s)));
        }

        w.start();
        w.stop();

        verify(sessionRepository, never()).insert(any());
    }

    @Test
    void run_sessionBelowMinimumSamples_isDiscarded() {
        final var w = workerWith(1000, 1, 10, Duration.ofSeconds(30));
        for (int i = 0; i < 5; i++) {
            w.offer(raw(racing(), i * 16 * MS));
        }

        w.start();
        w.stop();

        final var meta = ArgumentCaptor.forClass(SessionMeta.class);
        verify(sessionRepository).insert(meta.capture());
        verify(sessionRepository).delete(meta.getValue().id());
        verify(sessionRepository, never()).close(any(), any(), anyInt(), anyString());
    }

    @Test
    void run_downsample_keepsOneEveryNPackets() {
        final var w = workerWith(1000, 3, 1, Duration.ofSeconds(30));
        for (int i = 0; i < 9; i++) {
            w.offer(raw(racing(), i * 16 * MS));
        }

        w.start();
        w.stop();

        assertThat(persistedSamples()).hasSize(3);
    }

    @Test
    void run_sampleTimestamps_areStrictlyIncreasingEvenForBurstArrivals() {
        final var w = defaultWorker();
        for (int i = 0; i < 6; i++) {
            w.offer(raw(racing(), 5 * MS));
        }

        w.start();
        w.stop();

        final var times = persistedSamples().stream().map(SampleRow::tMs).toList();
        assertThat(times).hasSize(6).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void run_sledPackets_updateLiveSnapshotButAreNeverRecorded() {
        final var w = defaultWorker();
        w.offer(raw(PacketBuilder.racing(PacketFormat.SLED).build(), 0));

        w.start();
        w.stop();

        verify(sessionRepository, never()).insert(any());
        assertThat(liveSnapshot.current(Duration.ofSeconds(5))).isPresent();
    }

    @Test
    void run_raceOffPackets_doNotOpenSession() {
        final var w = defaultWorker();
        w.offer(raw(PacketBuilder.of(PacketFormat.HORIZON).raceOn(false).build(), 0));

        w.start();
        w.stop();

        verify(sessionRepository, never()).insert(any());
        assertThat(liveSnapshot.current(Duration.ofSeconds(5))).isPresent();
    }

    @Test
    void run_unknownSizePackets_areIgnoredWithoutBreakingTheWorker() {
        final var w = defaultWorker();
        w.offer(raw(new byte[100], 0));
        w.offer(raw(racing(), 16 * MS));

        w.start();
        w.stop();

        verify(sessionRepository).insert(any());
        assertThat(persistedSamples()).hasSize(1);
    }

    @Test
    void run_carChange_closesCurrentSessionAndOpensAnother() {
        final var w = defaultWorker();
        for (int i = 0; i < 3; i++) {
            w.offer(raw(PacketBuilder.racing(PacketFormat.HORIZON).speed(20f).car(1, 5, 800, 1, 8).build(), i * 16 * MS));
        }
        for (int i = 3; i < 6; i++) {
            w.offer(raw(PacketBuilder.racing(PacketFormat.HORIZON).speed(20f).car(2, 5, 800, 1, 8).build(), i * 16 * MS));
        }

        w.start();
        w.stop();

        final var metas = ArgumentCaptor.forClass(SessionMeta.class);
        verify(sessionRepository, times(2)).insert(metas.capture());
        assertThat(metas.getAllValues()).extracting(SessionMeta::carOrdinal).containsExactly(1, 2);
        verify(sessionRepository, times(2)).close(any(), any(), eq(3), anyString());
    }

    @Test
    void run_trackChangeOnForzaMotorsport_closesCurrentSession() {
        final var w = defaultWorker();
        w.offer(raw(PacketBuilder.racing(PacketFormat.FM_DASH).speed(20f).trackOrdinal(10).build(), 0));
        w.offer(raw(PacketBuilder.racing(PacketFormat.FM_DASH).speed(20f).trackOrdinal(10).build(), 16 * MS));
        w.offer(raw(PacketBuilder.racing(PacketFormat.FM_DASH).speed(20f).trackOrdinal(20).build(), 32 * MS));

        w.start();
        w.stop();

        verify(sessionRepository, times(2)).insert(any());
    }

    @Test
    void run_lapNumberIncrement_recordsFinishedLapTimeOfPreviousLap() {
        final var w = defaultWorker();
        w.offer(raw(PacketBuilder.racing(PacketFormat.HORIZON).speed(20f).lapNumber(1).lapTimes(0f, 0f, 10f).build(), 0));
        w.offer(raw(PacketBuilder.racing(PacketFormat.HORIZON).speed(20f).lapNumber(1).lapTimes(0f, 0f, 50f).build(), 16 * MS));
        w.offer(raw(PacketBuilder.racing(PacketFormat.HORIZON).speed(20f).lapNumber(2).lapTimes(62.5f, 62.5f, 0.5f).build(), 32 * MS));

        w.start();
        w.stop();

        final var meta = ArgumentCaptor.forClass(SessionMeta.class);
        verify(sessionRepository).insert(meta.capture());
        verify(lapRepository).upsert(meta.getValue().id(), 1, 62.5f);
        verify(lapRepository, times(1)).upsert(any(), anyInt(), org.mockito.ArgumentMatchers.anyFloat());
    }

    @Test
    void run_lapNumberIncrementWithoutLastLapTime_recordsNothing() {
        final var w = defaultWorker();
        w.offer(raw(PacketBuilder.racing(PacketFormat.HORIZON).speed(20f).lapNumber(1).build(), 0));
        w.offer(raw(PacketBuilder.racing(PacketFormat.HORIZON).speed(20f).lapNumber(2).lapTimes(0f, 0f, 1f).build(), 16 * MS));

        w.start();
        w.stop();

        verify(lapRepository, never()).upsert(any(), anyInt(), org.mockito.ArgumentMatchers.anyFloat());
    }

    @Test
    void offer_fullQueue_dropsExtraPacketsWithoutBlockingOrThrowing() {
        final var w = workerWith(2, 1, 1, Duration.ofSeconds(30));
        for (int i = 0; i < 5; i++) {
            w.offer(raw(racing(), i * 16 * MS));
        }

        w.start();
        w.stop();

        assertThat(persistedSamples()).hasSize(2);
    }

    @Test
    void run_failureOpeningSession_backsOffThenRetriesLater() {
        doThrow(new IllegalStateException("banco fora do ar")).doNothing().when(sessionRepository).insert(any());
        final var w = defaultWorker();
        w.offer(raw(racing(), 0));
        w.offer(raw(racing(), 1_000 * MS));
        w.offer(raw(racing(), TimeUnit.SECONDS.toNanos(10)));

        w.start();
        w.stop();

        verify(sessionRepository, times(2)).insert(any());
        assertThat(persistedSamples()).hasSize(1);
    }

    @Test
    void run_periodicFlushFailure_keepsPendingSamplesAndRetriesOnNextInterval() {
        doThrow(new IllegalStateException("falha de escrita")).doAnswer(copyBatch).when(sampleRepository).batchInsert(any(), any());
        final var w = defaultWorker();
        final var now = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            w.offer(raw(racing(), now + i * MS));
        }

        w.start();

        verify(sampleRepository, timeout(5000).times(2)).batchInsert(any(), any());
        assertThat(persistedSamples()).hasSize(3);
        assertThat(w.isRunning()).isTrue();
    }

    @Test
    void run_finalFlushFailsOnce_isRetriedAndSessionClosesWithAllSamples() {
        doThrow(new IllegalStateException("falha transitória")).doAnswer(copyBatch).when(sampleRepository).batchInsert(any(), any());
        final var w = defaultWorker();
        for (int i = 0; i < 3; i++) {
            w.offer(raw(racing(), i * 16 * MS));
        }

        w.start();
        w.stop();

        final var meta = ArgumentCaptor.forClass(SessionMeta.class);
        verify(sessionRepository).insert(meta.capture());
        assertThat(persistedSamples()).hasSize(3);
        verify(sessionRepository).close(eq(meta.getValue().id()), any(), eq(3), anyString());
    }

    @Test
    void run_finalFlushKeepsFailingAndNothingWasPersisted_sessionIsDeletedNotLeftOpen() {
        doThrow(new IllegalStateException("banco fora do ar")).when(sampleRepository).batchInsert(any(), any());
        final var w = defaultWorker();
        w.offer(raw(racing(), 0));

        w.start();
        w.stop();

        final var meta = ArgumentCaptor.forClass(SessionMeta.class);
        verify(sessionRepository).insert(meta.capture());
        verify(sessionRepository).delete(meta.getValue().id());
        verify(sessionRepository, never()).close(any(), any(), anyInt(), any());
        assertThat(w.isRunning()).isFalse();
    }

    @Test
    void run_finalFlushKeepsFailingButEarlierSamplesWerePersisted_sessionStillClosesWithPersistedCount() {
        doAnswer(copyBatch).doThrow(new IllegalStateException("banco fora do ar")).when(sampleRepository).batchInsert(any(), any());
        final var w = workerWith(1000, 1, 3, Duration.ofSeconds(30));
        final var now = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            w.offer(raw(racing(), now + i * MS));
        }
        w.start();
        verify(sampleRepository, timeout(5000)).batchInsert(any(), any());
        w.offer(raw(racing(), now + 10 * MS));
        w.offer(raw(racing(), now + 11 * MS));

        w.stop();

        final var meta = ArgumentCaptor.forClass(SessionMeta.class);
        verify(sessionRepository).insert(meta.capture());
        final var summary = ArgumentCaptor.forClass(String.class);
        verify(sessionRepository).close(eq(meta.getValue().id()), any(), eq(3), summary.capture());
        verify(sessionRepository, never()).delete(any());
    }

    @Test
    void run_inactivity_closesSessionWithoutStopping() {
        final var w = workerWith(1000, 1, 1, Duration.ofMillis(200));
        final var now = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            w.offer(raw(racing(), now + i * 16 * MS));
        }

        w.start();

        verify(sessionRepository, timeout(5000)).close(any(), any(), eq(3), anyString());
        assertThat(w.isRunning()).isTrue();
    }

    /** A inatividade conta o tempo real desde o último pacote processado — não o carimbo do pacote (nos testes, virtual). */
    @Test
    void run_packetTimestampsFarFromTheRealClock_doNotTriggerTheIdleTimeout() {
        final var w = defaultWorker();   // inatividade de 30 s
        for (int i = 0; i < 3; i++) {
            w.offer(raw(racing(), i * 16 * MS));   // carimbos "de 1970": o relógio real está muito à frente
        }

        w.start();

        verify(sessionRepository, timeout(5000)).insert(any());
        verify(sessionRepository, after(600).never()).close(any(), any(), anyInt(), anyString());
        assertThat(w.isRunning()).isTrue();
    }

    @Test
    void lifecycle_phaseIsLowerThanUdpListenerSoItStartsFirstAndStopsLast() {
        assertThat(defaultWorker().getPhase()).isLessThan(200);
    }

    @Test
    void lifecycle_isRunningReflectsStartAndStop() {
        final var w = defaultWorker();
        assertThat(w.isRunning()).isFalse();

        w.start();
        assertThat(w.isRunning()).isTrue();

        w.stop();
        assertThat(w.isRunning()).isFalse();
    }
}
