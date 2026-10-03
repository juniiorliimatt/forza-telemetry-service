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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final TuningHistoryRepository historyRepository;
    private final TuningAggregator aggregator;
    private final TuningAdvisor advisor;
    private final CarCatalog carCatalog;
    private final ObjectMapper objectMapper;
    private final TuningProperties properties;
    private final Clock clock;

    public TuningService(final SessionRepository sessionRepository,
                         final TuningCheckpointRepository checkpointRepository,
                         final TuningHistoryRepository historyRepository,
                         final TuningAggregator aggregator,
                         final TuningAdvisor advisor,
                         final CarCatalog carCatalog,
                         final ObjectMapper objectMapper,
                         final TuningProperties properties,
                         final Clock clock) {
        this.sessionRepository = sessionRepository;
        this.checkpointRepository = checkpointRepository;
        this.historyRepository = historyRepository;
        this.aggregator = aggregator;
        this.advisor = advisor;
        this.carCatalog = carCatalog;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** Um carro numa classe de PI: cada build é coletada (e recomendada) separadamente. */
    private record CarBuild(int carOrdinal, PerformanceClass performanceClass) {
    }

    /** Carros por classe de PI com sessões coletadas (depois do marco de cada um) e o progresso até poder recomendar. */
    public List<TuningCarDTO> cars() {
        final Map<CarBuild, List<SessionMeta>> byBuild = new LinkedHashMap<>();
        for (final SessionMeta meta : sessionRepository.findClosedMeta(GAME_FORMAT)) {
            byBuild.computeIfAbsent(new CarBuild(meta.carOrdinal(), PerformanceClass.of(meta.performanceIndex())), k -> new ArrayList<>()).add(meta);
        }
        final List<TuningCarDTO> cars = new ArrayList<>();
        byBuild.forEach((build, all) -> {
            final Instant since = checkpointRepository.find(GAME_FORMAT, build.carOrdinal(), build.performanceClass()).orElse(Instant.EPOCH);
            final List<SessionMeta> window = all.stream().filter(s -> !s.startedAt().isBefore(since)).limit(properties.maxSessions()).toList();
            if (window.isEmpty()) {
                return;
            }
            final SessionMeta latest = window.get(0);
            final long samples = window.stream().mapToLong(SessionMeta::sampleCount).sum();
            final boolean ready = window.size() >= properties.minSessions() && samples >= properties.minSamples();
            cars.add(new TuningCarDTO(build.carOrdinal(), carName(latest), latest.carClass(), latest.performanceIndex(), build.performanceClass().name(),
                    SessionDTO.from(latest).drivetrain(), window.size(), samples, properties.minSessions(), ready, latest.startedAt()));
        });
        cars.sort(Comparator.comparing(TuningCarDTO::lastSessionAt).reversed());
        return cars;
    }

    public TuningRecommendationDTO recommendation(final int carOrdinal, final PerformanceClass performanceClass) {
        final Optional<Instant> checkpoint = checkpointRepository.find(GAME_FORMAT, carOrdinal, performanceClass);
        final List<SessionMeta> metas = sessionRepository.findClosedForTuning(GAME_FORMAT, carOrdinal, performanceClass, checkpoint.orElse(Instant.EPOCH),
                properties.maxSessions());
        if (metas.isEmpty()) {
            throw new ResourceNotFoundException("Nenhuma sessão coletada para o carro " + carOrdinal);
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
                    readiness, windowFrom, windowTo, checkpoint.orElse(null), List.of(), List.of());
        }
        final TuningAdvisor.Advice advice = advisor.advise(aggregate, latest.drivetrain());
        return new TuningRecommendationDTO(carOrdinal, carName(latest), latest.carClass(), latest.performanceIndex(), performanceClass.name(), drivetrain,
                readiness, windowFrom, windowTo, checkpoint.orElse(null), advice.guides(), advice.thisCycle());
    }

    /**
     * Reinicia a coleta do carro: só sessões a partir de agora contam (use depois de aplicar um ajuste no jogo).
     * Se a recomendação já estava pronta, ela é salva no histórico antes — na mesma transação, então um erro ao
     * salvar não descarta a coleta (o marco não se move).
     */
    @Transactional
    public void resetCollection(final int carOrdinal, final PerformanceClass performanceClass) {
        saveToHistory(carOrdinal, performanceClass);
        checkpointRepository.upsert(GAME_FORMAT, carOrdinal, performanceClass, clock.instant());
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
                rec.checkpointAt(), rec.guides(), rec.thisCycle());
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
                    rec.readiness().samples(), rec.thisCycle().size(), objectMapper.writeValueAsString(rec)));
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
        return new TuningReadinessDTO(missing.isEmpty(), sessions, properties.minSessions(), samples, properties.minSamples(), missing);
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
