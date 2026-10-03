package br.com.forza.telemetry.ingest;

import br.com.forza.config.TelemetryProperties;
import br.com.forza.models.entities.SampleRow;
import br.com.forza.models.entities.SessionMeta;
import br.com.forza.repositories.LapRepository;
import br.com.forza.repositories.SampleRepository;
import br.com.forza.repositories.SessionRepository;
import br.com.forza.telemetry.PerformanceClass;
import br.com.forza.telemetry.packet.PacketParser;
import br.com.forza.telemetry.packet.TelemetryPacket;
import br.com.forza.telemetry.summary.SummaryCalculator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Consumidor único da fila de pacotes: decodifica, segmenta em sessões, detecta voltas,
 * faz downsample (60 → ~20 Hz) e grava em lote. Roda numa virtual thread própria, separada
 * da thread de recepção UDP, pra I/O de banco nunca atrasar o {@code receive()} (o buffer do
 * socket é pequeno e o jogo manda ~60 pacotes/s). Todo o estado de sessão é acessado só
 * por essa thread — sem locks.
 * <p>
 * Uma sessão começa no primeiro pacote com {@code IsRaceOn=1} <b>e o carro andando</b> (a garagem manda
 * {@code IsRaceOn=1} com o carro parado) e termina por inatividade ({@code telemetry.session-idle-timeout}),
 * carro parado por tempo demais ({@code telemetry.session-stationary-timeout}), troca de carro/pista ou shutdown. Sessões
 * curtas demais ({@code telemetry.min-session-samples}) são descartadas.
 */
@Component
@ConditionalOnProperty(name = "telemetry.udp.enabled", havingValue = "true", matchIfMissing = true)
public class IngestWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IngestWorker.class);
    private static final long OPEN_RETRY_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long JOIN_TIMEOUT_MS = 15_000;
    private static final int CLOSE_FLUSH_ATTEMPTS = 3;
    /** Abaixo disso (m/s) o carro conta como parado — garagem, menu, foto: não abre sessão e não gera amostra. */
    private static final float STOPPED_SPEED_MS = 0.5f;

    private final TelemetryProperties properties;
    private final LiveSnapshot liveSnapshot;
    private final SessionRepository sessionRepository;
    private final LapRepository lapRepository;
    private final SampleRepository sampleRepository;
    private final SummaryCalculator summaryCalculator;
    private final ObjectMapper objectMapper;
    private final BlockingQueue<RawPacket> queue;
    private final AtomicLong dropped = new AtomicLong();

    private volatile boolean running;
    private Thread thread;

    // Estado abaixo: acessado só pela thread do worker.
    private ActiveSession active;
    private long unknownSizeCount;
    private long errorCount;
    private boolean sledWarned;
    private long openRetryAfterNanos;

    public IngestWorker(final TelemetryProperties properties,
                        final LiveSnapshot liveSnapshot,
                        final SessionRepository sessionRepository,
                        final LapRepository lapRepository,
                        final SampleRepository sampleRepository,
                        final SummaryCalculator summaryCalculator,
                        final ObjectMapper objectMapper) {
        this.properties = properties;
        this.liveSnapshot = liveSnapshot;
        this.sessionRepository = sessionRepository;
        this.lapRepository = lapRepository;
        this.sampleRepository = sampleRepository;
        this.summaryCalculator = summaryCalculator;
        this.objectMapper = objectMapper;
        this.queue = new ArrayBlockingQueue<>(properties.udp().queueCapacity());
    }

    /** Chamado pela thread de recepção; nunca bloqueia — fila cheia descarta o pacote. */
    public void offer(final RawPacket packet) {
        if (!queue.offer(packet)) {
            final long total = dropped.incrementAndGet();
            if (total % 1000 == 1) {
                log.warn("Fila de ingestão cheia — pacote descartado (total descartado: {})", total);
            }
        }
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofVirtual().name("telemetry-ingest").start(this::run);
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Fase menor que a do listener UDP: sobe antes e, no shutdown, para depois (drena a fila). */
    @Override
    public int getPhase() {
        return 100;
    }

    private void run() {
        try {
            while (running) {
                final RawPacket raw = pollQuietly();
                if (raw != null) {
                    process(raw);
                }
                housekeeping(System.nanoTime());
            }
            RawPacket rest = queue.poll();
            while (rest != null) {
                process(rest);
                rest = queue.poll();
            }
        } finally {
            pauseActive();
        }
    }

    private RawPacket pollQuietly() {
        try {
            return queue.poll(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            running = false;
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void process(final RawPacket raw) {
        try {
            handle(raw);
        } catch (RuntimeException e) {
            // Banco fora do ar gera erro a cada pacote: loga o primeiro e depois 1 a cada 600 (~10 s).
            if (errorCount++ % 600 == 0) {
                log.error("Erro processando pacote de telemetria (ocorrências: {})", errorCount, e);
            }
        }
    }

    private void handle(final RawPacket raw) {
        final Optional<TelemetryPacket> parsed = PacketParser.parse(raw.data());
        if (parsed.isEmpty()) {
            if (unknownSizeCount++ % 1000 == 0) {
                log.warn("Pacote UDP de {} bytes não reconhecido (esperado 232, 311, 324 ou 331) — ignorados até agora: {}",
                        raw.data().length, unknownSizeCount);
            }
            return;
        }
        final TelemetryPacket packet = parsed.get();
        liveSnapshot.update(packet);

        if (!packet.format().hasDash()) {
            if (!sledWarned) {
                sledWarned = true;
                log.warn("Recebendo formato Sled (sem inputs, pneus nem posição) — nada será gravado. No Forza Motorsport, escolha o formato Dash.");
            }
            return;
        }
        if (!packet.raceOn()) {
            return;
        }

        if (active != null && changesSession(packet)) {
            closeActive("mudança de carro/pista/classe de PI");
        }
        // Janela cheia: fecha e a próxima abre na sequência — a menos que esteja numa corrida/evento, que espera acabar.
        if (active != null && active.storedSamples() >= properties.sessionMaxSamples() && !inRace(packet.dash())) {
            closeActive("limite de amostras");
        }
        final boolean stopped = packet.dash().speed() < STOPPED_SPEED_MS;
        if (active == null) {
            // Na garagem o jogo segue mandando IsRaceOn=1 com o carro parado: só abre sessão quando ele anda.
            if (stopped || raw.receivedNanos() < openRetryAfterNanos) {
                return;
            }
            active = resumeOrOpen(packet, raw.receivedNanos());
        }

        final TelemetryPacket.Dash dash = packet.dash();
        active.lastPacketNanos = raw.receivedNanos();
        trackLap(dash);

        // Carro parado (garagem, menu, largada) não gera amostra: só as amostras limitam a sessão e contam para o tuning.
        if (!stopped && active.rawCounter++ % properties.sampleEvery() == 0) {
            active.pending.add(toSample(packet, active, raw.receivedNanos()));
        }
        if (active.pending.size() >= properties.flushBatchSize()) {
            flush(active, raw.receivedNanos());
        }
    }

    /**
     * Corrida/evento: o número da volta é {@code >= 1} (no mundo aberto é 0 — conferido em amostras reais, em que 0
     * concentra a exploração a ~50 km/h e 1+ as voltas a 130–170 km/h). {@code racePosition} não é usado: não foi
     * validado no mundo aberto e um falso positivo impediria a rotação.
     */
    private static boolean inRace(final TelemetryPacket.Dash dash) {
        return dash.lapNumber() > 0;
    }

    private boolean changesSession(final TelemetryPacket packet) {
        return !sameBuild(packet, active.meta);
    }

    /**
     * Mesmo carro, mesma classe de PI (upgrade que muda a classe = outra build, para o tuning não misturar setups) e, no
     * Forza Motorsport, mesma pista.
     */
    private static boolean sameBuild(final TelemetryPacket packet, final SessionMeta meta) {
        if (packet.carOrdinal() != meta.carOrdinal()) {
            return false;
        }
        if (PerformanceClass.of(packet.performanceIndex()) != PerformanceClass.of(meta.performanceIndex())) {
            return false;
        }
        final Integer track = packet.dash().trackOrdinal();
        return track == null || meta.trackOrdinal() == null || track.equals(meta.trackOrdinal());
    }

    /**
     * Sem sessão ativa: retoma a que ficou aberta no banco (sair do jogo e voltar só amanhã, ou reiniciar o serviço, continua
     * na MESMA sessão) se for do mesmo carro/classe e ainda couber amostras — ou estiver numa corrida, que espera acabar. As
     * outras sessões abertas (outro carro/classe, ou já cheias) ficaram órfãs: são fechadas aqui, com resumo.
     */
    private ActiveSession resumeOrOpen(final TelemetryPacket packet, final long nowNanos) {
        final List<SessionMeta> open;
        try {
            open = sessionRepository.findActiveMeta(packet.format().label());
        } catch (RuntimeException e) {
            openRetryAfterNanos = nowNanos + OPEN_RETRY_BACKOFF_NANOS;
            throw e;
        }
        SessionMeta resumable = null;
        for (final SessionMeta meta : open) {   // da mais recente para a mais antiga
            final boolean fits = meta.sampleCount() < properties.sessionMaxSamples() || inRace(packet.dash());
            if (resumable == null && sameBuild(packet, meta) && fits) {
                resumable = meta;
            } else {
                closeOrphan(meta);
            }
        }
        return resumable == null ? openSession(packet, nowNanos) : resume(resumable, packet, nowNanos);
    }

    private ActiveSession resume(final SessionMeta meta, final TelemetryPacket packet, final long nowNanos) {
        final int lastTMs = sampleRepository.maxTMs(meta.id());
        // As novas amostras continuam logo depois da última já gravada (a pausa não vira tempo de sessão).
        final long startNanos = nowNanos - TimeUnit.MILLISECONDS.toNanos(lastTMs + 1L);
        final ActiveSession session = new ActiveSession(meta, startNanos, packet.dash().lapNumber());
        session.persisted = meta.sampleCount();
        session.lastTMs = lastTMs;
        log.info("Sessão {} retomada ({}, carro {}, PI {}): {} amostras já gravadas", meta.id(), meta.gameFormat(), meta.carOrdinal(),
                meta.performanceIndex(), meta.sampleCount());
        return session;
    }

    /** Fecha (com resumo) ou descarta (poucas amostras) uma sessão que ficou aberta no banco e não será retomada. */
    private void closeOrphan(final SessionMeta meta) {
        try {
            final List<SampleRow> rows = sampleRepository.findAll(meta.id());
            if (rows.size() < properties.minSessionSamples()) {
                sessionRepository.delete(meta.id());
                log.info("Sessão {} descartada (órfã): só {} amostras", meta.id(), rows.size());
                return;
            }
            final Instant endedAt = meta.startedAt().plusMillis(rows.get(rows.size() - 1).tMs()).truncatedTo(ChronoUnit.MICROS);
            sessionRepository.close(meta.id(), endedAt, rows.size(), summarize(meta));
            log.info("Sessão {} encerrada (órfã, outro carro/classe ou cheia): {} amostras", meta.id(), rows.size());
        } catch (RuntimeException e) {
            log.error("Falha ao encerrar a sessão órfã {}", meta.id(), e);
        }
    }

    /**
     * Desligamento do serviço: grava o que falta, mas NÃO fecha a sessão — ela segue aberta no banco e é retomada
     * quando o jogo voltar a enviar pacotes (ver {@link #resumeOrOpen}).
     */
    private void pauseActive() {
        final ActiveSession pausing = active;
        if (pausing == null) {
            return;
        }
        active = null;
        flushForClose(pausing);
        log.info("Sessão {} mantida aberta no desligamento ({} amostras gravadas)", pausing.meta.id(), pausing.persisted);
    }

    private ActiveSession openSession(final TelemetryPacket packet, final long nowNanos) {
        final TelemetryPacket.Dash dash = packet.dash();
        final SessionMeta meta = new SessionMeta(
                UUID.randomUUID(),
                packet.format().label(),
                packet.carOrdinal(),
                packet.carClass(),
                packet.performanceIndex(),
                packet.drivetrain(),
                packet.cylinders(),
                packet.engineMaxRpm(),
                packet.engineIdleRpm(),
                dash.trackOrdinal(),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                null,
                0,
                null);
        try {
            sessionRepository.insert(meta);
        } catch (RuntimeException e) {
            openRetryAfterNanos = nowNanos + OPEN_RETRY_BACKOFF_NANOS;
            throw e;
        }
        log.info("Sessão {} aberta ({}, carro {}, PI {})", meta.id(), meta.gameFormat(), meta.carOrdinal(), meta.performanceIndex());
        return new ActiveSession(meta, nowNanos, dash.lapNumber());
    }

    /** Ao incrementar LapNumber, o LastLap do pacote é o tempo da volta que acabou de fechar. */
    private void trackLap(final TelemetryPacket.Dash dash) {
        if (dash.lapNumber() > active.lastLapNumber && dash.lastLap() > 0f) {
            lapRepository.upsert(active.meta.id(), active.lastLapNumber, dash.lastLap());
        }
        active.lastLapNumber = dash.lapNumber();
    }

    private SampleRow toSample(final TelemetryPacket p, final ActiveSession session, final long receivedNanos) {
        final TelemetryPacket.Dash d = p.dash();
        // Estritamente crescente: pacotes que chegam em rajada (container com 1 CPU, GC, docker-proxy)
        // cairiam no mesmo milissegundo e seriam descartados pela PK (session_id, t_ms).
        final int tMs = Math.max((int) TimeUnit.NANOSECONDS.toMillis(receivedNanos - session.startNanos), session.lastTMs + 1);
        session.lastTMs = tMs;
        return new SampleRow(tMs, d.lapNumber(), p.currentRpm(), d.speed(), d.power(), d.torque(), d.boost(),
                d.gear(), d.accel(), d.brake(), d.steer(), p.accelerationX(), p.accelerationZ(),
                d.positionX(), d.positionZ(), p.onRumble(),
                p.suspension(), p.slipRatio(), p.slipAngle(), p.combinedSlip(), d.tireTemp(), d.tireWear());
    }

    private void housekeeping(final long nowNanos) {
        final ActiveSession session = active;
        if (session == null) {
            return;
        }
        // Sem regra de tempo para fechar: só amostras, troca de carro/classe/pista ou shutdown encerram a sessão.
        if (!session.pending.isEmpty() && nowNanos - session.lastFlushNanos >= properties.flushInterval().toNanos()) {
            try {
                flush(session, nowNanos);
            } catch (RuntimeException e) {
                log.error("Falha ao gravar lote de amostras da sessão {}", session.meta.id(), e);
                session.lastFlushNanos = nowNanos;
            }
        }
    }

    private void flush(final ActiveSession session, final long nowNanos) {
        if (session.pending.isEmpty()) {
            return;
        }
        sampleRepository.batchInsert(session.meta.id(), session.pending);
        session.persisted += session.pending.size();
        session.pending.clear();
        session.lastFlushNanos = nowNanos;
    }

    private void closeActive(final String reason) {
        final ActiveSession closing = active;
        if (closing == null) {
            return;
        }
        active = null;
        final SessionMeta meta = closing.meta;
        try {
            flushForClose(closing);
            if (closing.persisted < properties.minSessionSamples()) {
                sessionRepository.delete(meta.id());
                log.info("Sessão {} descartada ({}): só {} amostras", meta.id(), reason, closing.persisted);
                return;
            }
            final Instant endedAt = meta.startedAt()
                    .plusNanos(closing.lastPacketNanos - closing.startNanos)
                    .truncatedTo(ChronoUnit.MICROS);
            sessionRepository.close(meta.id(), endedAt, closing.persisted, summarize(meta));
            log.info("Sessão {} encerrada ({}): {} amostras", meta.id(), reason, closing.persisted);
        } catch (RuntimeException e) {
            log.error("Falha ao encerrar a sessão {}", meta.id(), e);
        }
    }

    /**
     * Flush final do fechamento: tenta algumas vezes (falha transitória de banco) e, se ainda
     * assim não gravar, registra a perda e segue — a sessão nunca fica aberta no banco por
     * causa de um lote pendente (fecha com o que já foi persistido, ou é descartada).
     */
    private void flushForClose(final ActiveSession session) {
        for (int attempt = 1; attempt <= CLOSE_FLUSH_ATTEMPTS; attempt++) {
            try {
                flush(session, System.nanoTime());
                return;
            } catch (RuntimeException e) {
                if (attempt == CLOSE_FLUSH_ATTEMPTS) {
                    log.error("Falha ao gravar o lote final da sessão {} — {} amostras pendentes perdidas",
                            session.meta.id(), session.pending.size(), e);
                }
            }
        }
    }

    /** Resumo de tuning serializado; se falhar, a sessão fecha sem resumo (recalculável via API). */
    private String summarize(final SessionMeta meta) {
        try {
            return objectMapper.writeValueAsString(summaryCalculator.calculate(
                    meta, sampleRepository.findAll(meta.id()), lapRepository.findBySession(meta.id())));
        } catch (JsonProcessingException | RuntimeException e) {
            log.error("Falha ao calcular o resumo da sessão {}", meta.id(), e);
            return null;
        }
    }

    private static final class ActiveSession {
        private final SessionMeta meta;
        private final long startNanos;
        private final List<SampleRow> pending = new ArrayList<>();
        private long lastPacketNanos;
        private long lastFlushNanos;
        private int rawCounter;
        private int lastTMs = -1;
        private int lastLapNumber;
        private int persisted;

        /** Amostras já gravadas mais as que aguardam o próximo lote. */
        private int storedSamples() {
            return persisted + pending.size();
        }

        private ActiveSession(final SessionMeta meta, final long startNanos, final int firstLapNumber) {
            this.meta = meta;
            this.startNanos = startNanos;
            this.lastPacketNanos = startNanos;
            this.lastFlushNanos = startNanos;
            this.lastLapNumber = firstLapNumber;
        }
    }
}
