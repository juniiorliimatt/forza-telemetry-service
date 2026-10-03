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
import static org.mockito.Mockito.when;

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
import java.time.Instant;
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

    private IngestWorker workerWith(final int queueCapacity, final int sampleEvery, final int minSamples) {
        return workerWith(queueCapacity, sampleEvery, minSamples, 5000);
    }

    private IngestWorker workerWith(final int queueCapacity, final int sampleEvery, final int minSamples, final int maxSamples) {
        final var properties = new TelemetryProperties(
                new TelemetryProperties.Udp(true, 5310, queueCapacity, 65_536),
                sampleEvery, maxSamples, Duration.ofMillis(50), 200, minSamples, Duration.ofSeconds(5));
        worker = new IngestWorker(properties, liveSnapshot, sessionRepository, lapRepository, sampleRepository,
                new SummaryCalculator(), new ObjectMapper());
        return worker;
    }

    private IngestWorker defaultWorker() {
        return workerWith(1000, 1, 1);
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

    private void verifyNeverClosed() {
        verify(sessionRepository, never()).close(any(), any(), anyInt(), anyString());
        verify(sessionRepository, never()).delete(any());
    }

    /** O desligamento do serviço grava o que falta, mas NÃO fecha a sessão: ela é retomada na próxima partida (ver os testes de retomada). */
    @Test
    void run_racingPackets_openAndPersistTheSession_leavingItOpenAtShutdown() {
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
        verifyNeverClosed();
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
        assertThat(persistedSamples()).hasSize(3);   // 2 + 1 andando; os 50 parados não enchem a janela de 3 → sem rotação
        verifyNeverClosed();
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
        assertThat(persistedSamples()).hasSize(3);
        verifyNeverClosed();
    }

    private IngestWorker rotatingWorker(final int maxSamples) {
        return workerWith(1000, 1, 1, maxSamples);
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
        verify(sessionRepository, times(2)).close(any(), any(), closed.capture(), anyString());
        assertThat(closed.getAllValues()).containsExactly(3, 3);   // a terceira (1 amostra) segue aberta: o desligamento não fecha
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
        assertThat(persistedSamples()).hasSize(7);
        verifyNeverClosed();
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
        verify(sessionRepository, times(1)).close(any(), any(), closed.capture(), anyString());
        assertThat(closed.getAllValues()).containsExactly(5);   // a corrida inteira numa sessão; o mundo aberto que vem depois segue aberto
    }

    @Test
    void run_rotationCountsStoredSamples_notRawPackets() {
        final var w = workerWith(1000, 3, 1, 3);
        for (int i = 0; i < 18; i++) {
            w.offer(raw(freeRoam(), i * 16 * MS));      // downsample 3: 1 amostra a cada 3 pacotes (o contador reinicia a cada sessão)
        }

        w.start();
        w.stop();

        final var closed = ArgumentCaptor.forClass(Integer.class);
        verify(sessionRepository, times(2)).close(any(), any(), closed.capture(), anyString());
        assertThat(closed.getAllValues()).containsExactly(3, 3);   // 8 amostras gravadas de 18 pacotes (3+3+2) — não 18 amostras
        verify(sessionRepository, times(3)).insert(any());
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
        verify(sessionRepository, times(1)).close(any(), any(), eq(3), anyString());   // só a da classe antiga; a nova segue aberta
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
    void run_sessionBelowMinimumSamples_isDiscardedWhenItCloses() {
        final var w = workerWith(1000, 1, 10);
        for (int i = 0; i < 5; i++) {
            w.offer(raw(PacketBuilder.racing(PacketFormat.HORIZON).speed(20f).car(1, 5, 800, 1, 8).build(), i * 16 * MS));
        }
        for (int i = 5; i < 8; i++) {
            w.offer(raw(PacketBuilder.racing(PacketFormat.HORIZON).speed(20f).car(2, 5, 800, 1, 8).build(), i * 16 * MS));   // troca de carro fecha a 1ª
        }

        w.start();
        w.stop();

        final var metas = ArgumentCaptor.forClass(SessionMeta.class);
        verify(sessionRepository, times(2)).insert(metas.capture());
        verify(sessionRepository).delete(metas.getAllValues().get(0).id());   // 5 amostras < mínimo de 10
        verify(sessionRepository, never()).close(any(), any(), anyInt(), anyString());
    }

    @Test
    void run_downsample_keepsOneEveryNPackets() {
        final var w = workerWith(1000, 3, 1);
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
        final var summary = ArgumentCaptor.forClass(String.class);
        verify(sessionRepository, times(1)).close(eq(metas.getAllValues().get(0).id()), any(), eq(3), summary.capture());   // a do carro 2 segue aberta
        assertThat(summary.getValue()).contains("\"samples\"");
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
        final var w = workerWith(2, 1, 1);
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
    void run_finalFlushFailsOnce_isRetriedAndAllSamplesAreSaved_theSessionStaysOpen() {
        doThrow(new IllegalStateException("falha transitória")).doAnswer(copyBatch).when(sampleRepository).batchInsert(any(), any());
        final var w = defaultWorker();
        for (int i = 0; i < 3; i++) {
            w.offer(raw(racing(), i * 16 * MS));
        }

        w.start();
        w.stop();

        verify(sessionRepository).insert(any());
        assertThat(persistedSamples()).hasSize(3);
        verifyNeverClosed();
    }

    @Test
    void run_finalFlushKeepsFailing_theLossIsLoggedButTheSessionIsNeitherClosedNorDeleted() {
        doThrow(new IllegalStateException("banco fora do ar")).when(sampleRepository).batchInsert(any(), any());
        final var w = defaultWorker();
        w.offer(raw(racing(), 0));

        w.start();
        w.stop();

        verify(sessionRepository).insert(any());
        verifyNeverClosed();   // a sessão fica aberta no banco e é retomada na próxima partida
        assertThat(w.isRunning()).isFalse();
    }

    @Test
    void run_finalFlushKeepsFailingButEarlierSamplesWerePersisted_theyStayAndTheSessionStaysOpen() {
        doAnswer(copyBatch).doThrow(new IllegalStateException("banco fora do ar")).when(sampleRepository).batchInsert(any(), any());
        final var w = workerWith(1000, 1, 3);
        final var now = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            w.offer(raw(racing(), now + i * MS));
        }
        w.start();
        verify(sampleRepository, timeout(5000)).batchInsert(any(), any());
        w.offer(raw(racing(), now + 10 * MS));
        w.offer(raw(racing(), now + 11 * MS));

        w.stop();

        verify(sessionRepository).insert(any());
        assertThat(persistedSamples()).hasSize(3);   // o lote anterior ficou gravado; o último foi perdido (logado)
        verifyNeverClosed();
    }

    /**
     * Só as amostras (e a troca de carro/classe) encerram a sessão — nenhuma regra de tempo. Pausar o jogo, ficar na garagem,
     * sair do jogo e voltar só amanhã ou até reiniciar o serviço não fecha nada: a sessão continua (ver os testes de retomada).
     */
    @Test
    void run_aLongGapBetweenPackets_keepsTheSameSession() {
        final var w = defaultWorker();
        w.offer(raw(racing(), 0));
        w.offer(raw(racing(), 16 * MS));
        w.offer(raw(racing(), seconds(3 * 3600)));        // 3 h depois (jogo pausado, PC suspenso…)
        w.offer(raw(racing(), seconds(3 * 3600) + 16 * MS));

        w.start();
        w.stop();

        verify(sessionRepository, times(1)).insert(any());
        assertThat(persistedSamples()).hasSize(4);
        verifyNeverClosed();
    }

    @Test
    void run_noPacketsForAWhile_doesNotCloseTheSession_noteventAtShutdown() {
        final var w = defaultWorker();
        for (int i = 0; i < 3; i++) {
            w.offer(raw(racing(), i * 16 * MS));
        }

        w.start();

        verify(sessionRepository, timeout(5000)).insert(any());
        verify(sessionRepository, after(800).never()).close(any(), any(), anyInt(), anyString());
        assertThat(w.isRunning()).isTrue();
        w.stop();
        verifyNeverClosed();
    }

    @Test
    void run_pausedGame_racePacketsOffThenResumed_continuesInTheSameSession() {
        final var w = defaultWorker();
        w.offer(raw(racing(), 0));
        w.offer(raw(PacketBuilder.of(PacketFormat.HORIZON).raceOn(false).build(), seconds(60)));   // pausado
        w.offer(raw(PacketBuilder.of(PacketFormat.HORIZON).raceOn(false).build(), seconds(600)));  // ainda pausado
        w.offer(raw(racing(), seconds(900)));                                                       // voltou

        w.start();
        w.stop();

        verify(sessionRepository, times(1)).insert(any());
        assertThat(persistedSamples()).hasSize(2);
        verifyNeverClosed();
    }

    // ---- Retomada: sair do jogo hoje e voltar só amanhã (ou reiniciar o serviço) continua na MESMA sessão ----

    private static SampleRow sample(final int tMs) {
        return br.com.forza.support.Fixtures.sample().tMs(tMs).build();
    }

    private static SessionMeta openSession(final int car, final int pi, final int storedSamples) {
        return new SessionMeta(UUID.randomUUID(), "FH4/FH5/FH6", car, 5, pi, 1, 8, 8000f, 1000f, null,
                Instant.parse("2026-10-03T12:00:00Z"), null, storedSamples, null);
    }

    @Test
    void run_anOpenSessionOfTheSameCarAndClass_isResumedInsteadOfOpeningANewOne() {
        final var open = openSession(1234, 800, 1200);
        when(sessionRepository.findActiveMeta("FH4/FH5/FH6")).thenReturn(List.of(open));
        when(sampleRepository.maxTMs(open.id())).thenReturn(60_000);
        final var w = defaultWorker();
        w.offer(raw(racing(), 0));
        w.offer(raw(racing(), 16 * MS));

        w.start();
        w.stop();

        verify(sessionRepository, never()).insert(any());
        assertThat(persistedSamples()).hasSize(2);
        assertThat(persistedSamples()).extracting(SampleRow::tMs).allSatisfy(t -> assertThat(t).isGreaterThan(60_000));   // continua depois do último
        assertThat(persistedSamples().get(1).tMs()).isGreaterThan(persistedSamples().get(0).tMs());
        verifyNeverClosed();
    }

    @Test
    void run_aResumedSession_countsItsEarlierSamplesTowardTheRotationLimit() {
        final var open = openSession(1234, 800, 2);
        // 1ª consulta: a sessão aberta; depois de fechada pela rotação, o banco não a devolve mais
        when(sessionRepository.findActiveMeta("FH4/FH5/FH6")).thenReturn(List.of(open)).thenReturn(List.of());
        when(sampleRepository.maxTMs(open.id())).thenReturn(5_000);
        final var w = rotatingWorker(3);
        for (int i = 0; i < 3; i++) {
            w.offer(raw(freeRoam(), i * 16 * MS));
        }

        w.start();
        w.stop();

        // 2 já gravadas + 1 nova = 3 → fecha ao próximo pacote; os outros 2 abrem uma sessão nova
        final var closed = ArgumentCaptor.forClass(Integer.class);
        verify(sessionRepository, times(1)).close(eq(open.id()), any(), closed.capture(), anyString());
        assertThat(closed.getValue()).isEqualTo(3);
        verify(sessionRepository, times(1)).insert(any());
    }

    @Test
    void run_anOpenSessionOfAnotherCar_isClosedAndANewOneOpens() {
        final var stale = openSession(999, 800, 400);
        when(sessionRepository.findActiveMeta("FH4/FH5/FH6")).thenReturn(List.of(stale));
        when(sampleRepository.findAll(stale.id())).thenReturn(List.of(sample(1000), sample(2000)));
        final var w = defaultWorker();
        w.offer(raw(racing(), 0));

        w.start();
        w.stop();

        verify(sessionRepository).close(eq(stale.id()), any(), eq(2), anyString());
        verify(sessionRepository, times(1)).insert(any());
    }

    @Test
    void run_anOpenSessionOfTheSameCarInAnotherPerformanceClass_isClosedNotResumed() {
        final var stale = openSession(1234, 650, 400);   // A; o pacote é PI 800 (S1)
        when(sessionRepository.findActiveMeta("FH4/FH5/FH6")).thenReturn(List.of(stale));
        when(sampleRepository.findAll(stale.id())).thenReturn(List.of(sample(1000), sample(2000)));
        final var w = defaultWorker();
        w.offer(raw(racing(), 0));

        w.start();
        w.stop();

        verify(sessionRepository).close(eq(stale.id()), any(), eq(2), anyString());
        verify(sessionRepository, times(1)).insert(any());
    }

    @Test
    void run_aStaleSessionWithTooFewSamples_isDeletedWhenItIsClosed() {
        final var stale = openSession(999, 800, 3);
        when(sessionRepository.findActiveMeta("FH4/FH5/FH6")).thenReturn(List.of(stale));
        when(sampleRepository.findAll(stale.id())).thenReturn(List.of(sample(10)));
        final var w = workerWith(1000, 1, 10);
        w.offer(raw(racing(), 0));

        w.start();
        w.stop();

        verify(sessionRepository).delete(stale.id());
        verify(sessionRepository, never()).close(eq(stale.id()), any(), anyInt(), anyString());
    }

    @Test
    void run_anOpenSessionAlreadyFull_isClosedAndANewOneOpens() {
        final var full = openSession(1234, 800, 3);
        when(sessionRepository.findActiveMeta("FH4/FH5/FH6")).thenReturn(List.of(full));
        when(sampleRepository.findAll(full.id())).thenReturn(List.of(sample(1000), sample(2000), sample(3000)));
        final var w = rotatingWorker(3);
        w.offer(raw(freeRoam(), 0));

        w.start();
        w.stop();

        verify(sessionRepository).close(eq(full.id()), any(), eq(3), anyString());
        verify(sessionRepository, times(1)).insert(any());
    }

    @Test
    void run_anOpenSessionAlreadyFull_butInARace_isResumedUntilTheRaceEnds() {
        final var full = openSession(1234, 800, 3);
        when(sessionRepository.findActiveMeta("FH4/FH5/FH6")).thenReturn(List.of(full));
        when(sampleRepository.maxTMs(full.id())).thenReturn(3_000);
        final var w = rotatingWorker(3);
        w.offer(raw(inRace(), 0));

        w.start();
        w.stop();

        verify(sessionRepository, never()).insert(any());
        verifyNeverClosed();
    }

    @Test
    void run_stationaryPacketsDoNotResumeAnything() {
        when(sessionRepository.findActiveMeta("FH4/FH5/FH6")).thenReturn(List.of(openSession(1234, 800, 100)));
        final var w = defaultWorker();
        w.offer(raw(parked(), 0));

        w.start();
        w.stop();

        verify(sessionRepository, never()).insert(any());
        assertThat(persistedSamples()).isEmpty();
        verifyNeverClosed();
    }

    @Test
    void run_ifLookingForOpenSessionsFails_itBacksOffLikeAFailedOpen_andRetriesLater() {
        when(sessionRepository.findActiveMeta("FH4/FH5/FH6")).thenThrow(new IllegalStateException("banco fora do ar")).thenReturn(List.of());
        final var w = defaultWorker();
        w.offer(raw(racing(), 0));
        w.offer(raw(racing(), 1_000 * MS));                      // dentro do recuo de 5 s: ignorado
        w.offer(raw(racing(), TimeUnit.SECONDS.toNanos(10)));    // depois do recuo: abre

        w.start();
        w.stop();

        verify(sessionRepository, times(1)).insert(any());
        assertThat(persistedSamples()).hasSize(1);
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
