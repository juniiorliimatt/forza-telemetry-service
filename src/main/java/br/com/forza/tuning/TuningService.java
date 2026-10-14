package br.com.forza.tuning;

import br.com.forza.config.TuningProperties;
import br.com.forza.exceptions.ResourceNotFoundException;
import br.com.forza.models.dto.SessionDTO;
import br.com.forza.models.dto.TuningCarDTO;
import br.com.forza.models.dto.TuningHistoryDTO;
import br.com.forza.models.dto.TuningHistoryItemDTO;
import br.com.forza.models.dto.TuningRecommendationDTO;
import br.com.forza.models.dto.TuningRecommendationDTO.TuningReadinessDTO;
import br.com.forza.models.dto.TuningSummaryDTO;
import br.com.forza.models.entities.SessionMeta;
import br.com.forza.repositories.SessionRepository;
import br.com.forza.repositories.TuningCheckpointRepository;
import br.com.forza.repositories.TuningHistoryRepository;
import br.com.forza.telemetry.CarCatalog;
import br.com.forza.telemetry.PerformanceClass;
import br.com.forza.telemetry.packet.PacketFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomendação de tuning por carro a partir das sessões coletadas. Escopo: família Horizon (FH4/FH5/FH6 usam o
 * mesmo pacote, então não dá pra separar o jogo). Só gera recomendação com dados suficientes
 * ({@link TuningProperties}) e só considera sessões depois do marco de coleta do carro — o Data Out não traz os
 * valores do setup, então quem muda o setup reinicia a coleta ({@link #resetCollection}).
 */
@Service
public class TuningService {

    private static final String GAME_FORMAT = PacketFormat.HORIZON.label();

    private final SessionRepository sessionRepository;
    private final TuningCheckpointRepository checkpointRepository;
    private final CarSetupStore setupStore;
    private final TuningHistoryRepository historyRepository;
    private final TuningAggregator aggregator;
    private final TuningAdvisor advisor;
    private final CarCatalog carCatalog;
    private final ObjectMapper objectMapper;
    private final TuningProperties properties;
    private final SamplePurger samplePurger;
    private final Clock clock;

    public TuningService(final SessionRepository sessionRepository,
                         final TuningCheckpointRepository checkpointRepository,
                         final CarSetupStore setupStore,
                         final TuningHistoryRepository historyRepository,
                         final TuningAggregator aggregator,
                         final TuningAdvisor advisor,
                         final CarCatalog carCatalog,
                         final ObjectMapper objectMapper,
                         final TuningProperties properties,
                         final SamplePurger samplePurger,
                         final Clock clock) {
        this.sessionRepository = sessionRepository;
        this.checkpointRepository = checkpointRepository;
        this.setupStore = setupStore;
        this.historyRepository = historyRepository;
        this.aggregator = aggregator;
        this.advisor = advisor;
        this.carCatalog = carCatalog;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.samplePurger = samplePurger;
        this.clock = clock;
    }

    /** Um carro numa classe de PI: cada build é coletada (e recomendada) separadamente. */
    private record CarBuild(int carOrdinal, PerformanceClass performanceClass) {
    }

    private static CarBuild buildOf(final SessionMeta meta) {
        return new CarBuild(meta.carOrdinal(), PerformanceClass.of(meta.performanceIndex()));
    }

    /**
     * Carros por classe de PI com sessões coletadas (depois do marco de cada um) e o progresso até poder recomendar.
     * A sessão em andamento aparece à parte ({@code activeSession}) e não entra no progresso até fechar; um carro só
     * com sessão em andamento também é listado (progresso zero).
     */
    public List<TuningCarDTO> cars() {
        final Map<CarBuild, List<SessionMeta>> closedByBuild = new LinkedHashMap<>();
        for (final SessionMeta meta : sessionRepository.findClosedMeta(GAME_FORMAT)) {
            closedByBuild.computeIfAbsent(buildOf(meta), k -> new ArrayList<>()).add(meta);
        }
        final Map<CarBuild, SessionMeta> activeByBuild = new LinkedHashMap<>();
        for (final SessionMeta meta : sessionRepository.findActiveMeta(GAME_FORMAT)) {
            activeByBuild.putIfAbsent(buildOf(meta), meta);   // vem da mais recente para a mais antiga
        }
        final Set<CarBuild> builds = new LinkedHashSet<>(closedByBuild.keySet());
        builds.addAll(activeByBuild.keySet());

        final List<TuningCarDTO> cars = new ArrayList<>();
        for (final CarBuild build : builds) {
            final Instant since = checkpointRepository.find(GAME_FORMAT, build.carOrdinal(), build.performanceClass()).orElse(Instant.EPOCH);
            // Sem setup aceito pelo usuário a coleta não começou: nenhuma sessão conta, mas o carro aparece para ele poder conferir o setup.
            final boolean confirmed = setupStore.find(build.carOrdinal(), build.performanceClass()).isPresent();
            final List<SessionMeta> closed = closedByBuild.getOrDefault(build, List.of());
            final List<SessionMeta> window = !confirmed ? List.of() : closed.stream()
                    .filter(s -> !s.startedAt().isBefore(since)).limit(properties.maxSessions()).toList();
            final SessionMeta open = activeByBuild.get(build);
            final TuningCarDTO.ActiveSessionDTO active = !confirmed || open == null || open.startedAt().isBefore(since) ? null
                    : new TuningCarDTO.ActiveSessionDTO(open.sampleCount(), properties.sessionSamples(), open.startedAt());
            // Todo carro com sessão aparece, mesmo logo após reiniciar a coleta (progresso zero): senão ele some da lista até a
            // próxima sessão e o usuário não consegue abrir o setup que acabou de salvar.
            final SessionMeta latest = !window.isEmpty() ? window.get(0) : !closed.isEmpty() ? closed.get(0) : open;
            final long samples = window.stream().mapToLong(SessionMeta::sampleCount).sum();
            final boolean ready = window.size() >= properties.minSessions() && samples >= properties.minSamples();
            final boolean highConfidence = ready && isHighConfidence(window.size(), samples);
            cars.add(new TuningCarDTO(build.carOrdinal(), carName(latest), latest.carClass(), latest.performanceIndex(), build.performanceClass().name(),
                    SessionDTO.from(latest).drivetrain(), window.size(), samples, properties.minSessions(), properties.minSamples(), ready,
                    highConfidence, properties.highMinSessions(), properties.highMinSamples(), latest.startedAt(), active, confirmed));
        }
        cars.sort(Comparator.comparing(TuningCarDTO::lastSessionAt).reversed());
        return cars;
    }

    public TuningRecommendationDTO recommendation(final int carOrdinal, final PerformanceClass performanceClass) {
        final Optional<CarSetupStore.CarSetup> setup = setupStore.find(carOrdinal, performanceClass);
        if (setup.isEmpty()) {
            return awaitingSetup(carOrdinal, performanceClass);
        }
        final Optional<Instant> checkpoint = checkpointRepository.find(GAME_FORMAT, carOrdinal, performanceClass);
        final List<SessionMeta> metas = sessionRepository.findClosedForTuning(GAME_FORMAT, carOrdinal, performanceClass, checkpoint.orElse(Instant.EPOCH),
                properties.maxSessions());
        if (metas.isEmpty()) {
            // Só tem a sessão em andamento (ou nenhuma desde o marco): o carro está na lista (cars()), então responde "coletando"
            // com progresso zero — é justamente quando a configuração inicial mais ajuda, e a tela precisa abrir (aba Setup incluída)
            // logo depois de aceitar o setup, quando a coleta recomeça do zero. 404 só se o carro nunca teve sessão nessa classe.
            final SessionMeta open = Optional.ofNullable(openSessionSince(carOrdinal, performanceClass, checkpoint.orElse(Instant.EPOCH)))
                    .orElseGet(() -> latestSession(carOrdinal, performanceClass));
            return new TuningRecommendationDTO(carOrdinal, carName(open), open.carClass(), open.performanceIndex(), performanceClass.name(),
                    SessionDTO.from(open).drivetrain(), readiness(0, 0), null, null, checkpoint.orElse(null), List.of(), List.of(), TuningBaseline.setup(), true);
        }
        final List<TuningAggregator.SessionSummary> usable = new ArrayList<>();
        final List<SessionMeta> usableMetas = new ArrayList<>();
        for (final SessionMeta meta : metas) {
            parse(meta.summaryJson()).ifPresent(summary -> {
                usable.add(new TuningAggregator.SessionSummary(meta.sampleCount(), summary));
                usableMetas.add(meta);
            });
        }
        final SessionMeta latest = metas.get(0);
        final TuningAggregate aggregate = aggregator.aggregate(usable);
        final TuningReadinessDTO readiness = readiness(aggregate.sessions(), aggregate.samples());
        final Instant windowFrom = usableMetas.stream().map(SessionMeta::startedAt).min(Comparator.naturalOrder()).orElse(latest.startedAt());
        final Instant windowTo = usableMetas.stream().map(SessionMeta::startedAt).max(Comparator.naturalOrder()).orElse(latest.startedAt());
        final String drivetrain = SessionDTO.from(latest).drivetrain();

        if (!readiness.ready()) {
            return new TuningRecommendationDTO(carOrdinal, carName(latest), latest.carClass(), latest.performanceIndex(), performanceClass.name(), drivetrain,
                    readiness, windowFrom, windowTo, checkpoint.orElse(null), List.of(), List.of(), TuningBaseline.setup(), true);
        }
        final TuningAdvisor.Advice advice = SetupTargets.apply(advisor.advise(aggregate, latest.drivetrain()), setup.get().values(), setup.get().ranges());
        return new TuningRecommendationDTO(carOrdinal, carName(latest), latest.carClass(), latest.performanceIndex(), performanceClass.name(), drivetrain,
                readiness, windowFrom, windowTo, checkpoint.orElse(null), advice.guides(), advice.thisCycle(), TuningBaseline.setup(), true);
    }

    /**
     * O usuário ainda não conferiu e aceitou o setup deste carro/classe: a coleta não começou, então nenhuma sessão conta e a
     * recomendação é só a configuração inicial — o front leva ao formulário de setup. O carro precisa ter ao menos uma sessão
     * (fechada ou em andamento) para existir na lista; sem nenhuma, 404 como sempre.
     */
    private TuningRecommendationDTO awaitingSetup(final int carOrdinal, final PerformanceClass performanceClass) {
        final SessionMeta latest = latestSession(carOrdinal, performanceClass);
        final TuningReadinessDTO base = readiness(0, 0);
        final TuningReadinessDTO readiness = new TuningReadinessDTO(false, 0, base.requiredSessions(), 0, base.requiredSamples(), false,
                base.highRequiredSessions(), base.highRequiredSamples(),
                List.of("Confira o setup do carro e aceite os valores para começar a coleta."));
        return new TuningRecommendationDTO(carOrdinal, carName(latest), latest.carClass(), latest.performanceIndex(), performanceClass.name(),
                SessionDTO.from(latest).drivetrain(), readiness, null, null, null, List.of(), List.of(), TuningBaseline.setup(), false);
    }

    /**
     * Reinicia a coleta do carro: só sessões a partir de agora contam (use depois de aplicar um ajuste no jogo).
     * Se a recomendação já estava pronta, ela é salva no histórico antes — na mesma transação, então um erro ao
     * salvar não descarta a coleta (o marco não se move). Depois do marco, apaga as amostras brutas das sessões desse
     * carro/classe ({@link SamplePurger}) — o tuning já está no histórico e o resumo de cada sessão fica guardado.
     */
    @Transactional
    public void resetCollection(final int carOrdinal, final PerformanceClass performanceClass) {
        saveToHistory(carOrdinal, performanceClass);
        checkpointRepository.upsert(GAME_FORMAT, carOrdinal, performanceClass, clock.instant());
        samplePurger.purge(GAME_FORMAT, carOrdinal, performanceClass);
    }

    /** Tunings salvos, do mais recente para o mais antigo. */
    public List<TuningHistoryItemDTO> history() {
        return historyRepository.findAll(GAME_FORMAT).stream()
                .map(e -> new TuningHistoryItemDTO(e.id(), e.carOrdinal(), e.carName(), e.carClass(), e.performanceIndex(),
                        PerformanceClass.of(e.performanceIndex()).name(), e.drivetrain(),
                        e.createdAt(), e.windowFrom(), e.windowTo(), e.sessions(), e.samples(), e.adjustments()))
                .toList();
    }

    /** A recomendação como estava quando foi salva (independe das sessões atuais). */
    public TuningHistoryDTO historyEntry(final UUID id) {
        final TuningHistoryRepository.Entry entry = historyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tuning salvo não encontrado: " + id));
        try {
            return new TuningHistoryDTO(entry.id(), entry.createdAt(), withClass(objectMapper.readValue(entry.recommendationJson(), TuningRecommendationDTO.class)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Tuning salvo ilegível: " + id, e);
        }
    }

    /** Fotos salvas antes da separação por classe não trazem {@code performanceClass}: deriva do PI gravado. */
    private static TuningRecommendationDTO withClass(final TuningRecommendationDTO rec) {
        if (rec.performanceClass() != null) {
            return rec;
        }
        return new TuningRecommendationDTO(rec.carOrdinal(), rec.carName(), rec.carClass(), rec.performanceIndex(),
                PerformanceClass.of(rec.performanceIndex()).name(), rec.drivetrain(), rec.readiness(), rec.windowFrom(), rec.windowTo(),
                rec.checkpointAt(), rec.guides(), rec.thisCycle(), rec.initialSetup(), rec.setupConfirmed());
    }

    /** A sessão mais recente do carro nessa classe (fechada, senão em andamento), de qualquer época: dá a identidade do carro. 404 se nunca houve. */
    private SessionMeta latestSession(final int carOrdinal, final PerformanceClass performanceClass) {
        final SessionMeta latest = sessionRepository.findClosedForTuning(GAME_FORMAT, carOrdinal, performanceClass, Instant.EPOCH, 1).stream().findFirst()
                .orElseGet(() -> openSessionSince(carOrdinal, performanceClass, Instant.EPOCH));
        if (latest == null) {
            throw new ResourceNotFoundException("Nenhuma sessão coletada para o carro " + carOrdinal);
        }
        return latest;
    }

    /** Sessão em andamento desse carro/classe iniciada a partir de {@code since} (marco da coleta); nula se não houver. */
    private SessionMeta openSessionSince(final int carOrdinal, final PerformanceClass performanceClass, final Instant since) {
        final CarBuild build = new CarBuild(carOrdinal, performanceClass);
        return sessionRepository.findActiveMeta(GAME_FORMAT).stream()
                .filter(meta -> buildOf(meta).equals(build) && !meta.startedAt().isBefore(since))
                .findFirst()
                .orElse(null);
    }

    /** A configuração inicial é fixa e só existe na tela ao vivo; a foto guarda só o que foi medido e recomendado. */
    private static TuningRecommendationDTO withoutInitialSetup(final TuningRecommendationDTO rec) {
        return new TuningRecommendationDTO(rec.carOrdinal(), rec.carName(), rec.carClass(), rec.performanceIndex(), rec.performanceClass(),
                rec.drivetrain(), rec.readiness(), rec.windowFrom(), rec.windowTo(), rec.checkpointAt(), rec.guides(), rec.thisCycle(), List.of(),
                rec.setupConfirmed());
    }

    private void saveToHistory(final int carOrdinal, final PerformanceClass performanceClass) {
        final TuningRecommendationDTO rec;
        try {
            rec = recommendation(carOrdinal, performanceClass);
        } catch (ResourceNotFoundException e) {
            return;
        }
        if (!rec.readiness().ready()) {
            return;
        }
        try {
            historyRepository.insert(new TuningHistoryRepository.Entry(UUID.randomUUID(), GAME_FORMAT, carOrdinal, rec.carName(), rec.carClass(),
                    rec.performanceIndex(), rec.drivetrain(), clock.instant(), rec.windowFrom(), rec.windowTo(), rec.readiness().sessions(),
                    rec.readiness().samples(), rec.thisCycle().size(), objectMapper.writeValueAsString(withoutInitialSetup(rec))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Não foi possível serializar a recomendação do carro " + carOrdinal, e);
        }
    }

    private TuningReadinessDTO readiness(final int sessions, final long samples) {
        final List<String> missing = new ArrayList<>();
        if (sessions < properties.minSessions()) {
            final int need = properties.minSessions() - sessions;
            missing.add("Faltam " + need + (need == 1 ? " sessão" : " sessões") + " com este carro (" + sessions + " de " + properties.minSessions() + ").");
        }
        if (samples < properties.minSamples()) {
            final long needSamples = properties.minSamples() - samples;
            missing.add("Faltam " + needSamples + " amostras (" + samples + " de " + properties.minSamples() + ").");
        }
        final boolean ready = missing.isEmpty();
        return new TuningReadinessDTO(ready, sessions, properties.minSessions(), samples, properties.minSamples(), ready && isHighConfidence(sessions, samples),
                properties.highMinSessions(), properties.highMinSamples(), missing);
    }

    /** Nível de ajuste fino: sessões E amostras, como no nível inicial. */
    private boolean isHighConfidence(final int sessions, final long samples) {
        return sessions >= properties.highMinSessions() && samples >= properties.highMinSamples();
    }

    private Optional<TuningSummaryDTO> parse(final String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, TuningSummaryDTO.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private String carName(final SessionMeta meta) {
        return carCatalog.nameOf(meta.gameFormat(), meta.carOrdinal()).orElse(null);
    }
}
