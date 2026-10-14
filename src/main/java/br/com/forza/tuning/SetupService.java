package br.com.forza.tuning;

import br.com.forza.exceptions.InvalidSetupException;
import br.com.forza.exceptions.ResourceNotFoundException;
import br.com.forza.exceptions.SetupConflictException;
import br.com.forza.models.dto.SetupDTO;
import br.com.forza.models.dto.SetupRequest;
import br.com.forza.models.dto.TuningRecommendationDTO;
import br.com.forza.models.dto.TuningRecommendationDTO.TuningSuggestionDTO;
import br.com.forza.telemetry.PerformanceClass;
import br.com.forza.tuning.CarSetupStore.CarSetup;
import br.com.forza.tuning.SetupCatalog.Range;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Setup atual de cada carro/classe de PI: o usuário o informa (o Data Out não traz), confere o rascunho da receita inicial e aceita
 * para começar a coleta, e "aplica" os alvos do ciclo depois de mexer no jogo. Toda mudança de valor reinicia a coleta
 * ({@link TuningService#resetCollection}): as amostras antigas são de outro setup. A foto do histórico sai antes de o setup mudar.
 */
@Service
public class SetupService {

    private static final int DEFAULT_GEAR_COUNT = 6;

    private final CarSetupStore store;
    private final TuningService tuningService;
    private final Clock clock;

    public SetupService(final CarSetupStore store, final TuningService tuningService, final Clock clock) {
        this.store = store;
        this.tuningService = tuningService;
        this.clock = clock;
    }

    /** O setup confirmado, ou o rascunho pré-preenchido com a receita inicial (ainda não salvo). */
    public SetupDTO get(final int carOrdinal, final PerformanceClass performanceClass) {
        return store.find(carOrdinal, performanceClass).map(SetupService::toDto).orElseGet(SetupService::draft);
    }

    /** Valida, salva e aceita o setup. Sem mudança em relação ao já salvo, não mexe na coleta. */
    @Transactional
    public SetupDTO save(final int carOrdinal, final PerformanceClass performanceClass, final SetupRequest request) {
        final int gearCount = request.gearCount() == null ? 0 : request.gearCount();
        final Map<String, Double> values = request.values() == null ? Map.of() : request.values();
        final Map<String, Range> ranges = request.ranges() == null ? Map.of() : request.ranges();
        final List<String> errors = SetupCatalog.validate(gearCount, values, ranges);
        if (!errors.isEmpty()) {
            throw new InvalidSetupException(errors);
        }
        return persist(carOrdinal, performanceClass, store.find(carOrdinal, performanceClass), new CarSetup(gearCount, new LinkedHashMap<>(values),
                new LinkedHashMap<>(ranges), clock.instant()));
    }

    /**
     * "Apliquei": grava os alvos do ciclo atual como os novos valores atuais e reinicia a coleta. Só os que têm valor-alvo e ainda
     * podem se mover entram — o que o usuário mexeu a mais no jogo ele corrige à mão pelo PUT.
     */
    @Transactional
    public SetupDTO apply(final int carOrdinal, final PerformanceClass performanceClass) {
        final CarSetup current = store.find(carOrdinal, performanceClass)
                .orElseThrow(() -> new SetupConflictException("Confira e aceite o setup do carro antes de aplicar ajustes."));
        final TuningRecommendationDTO recommendation = tuningService.recommendation(carOrdinal, performanceClass);
        final Map<String, Double> values = new LinkedHashMap<>(current.values());
        boolean changed = false;
        for (final TuningSuggestionDTO suggestion : recommendation.thisCycle()) {
            if (suggestion.setupKey() != null && suggestion.target() != null && !suggestion.atLimit()) {
                values.put(suggestion.setupKey(), suggestion.target());
                changed = true;
            }
        }
        if (!changed) {
            throw new SetupConflictException("Não há ajuste com valor-alvo para aplicar neste ciclo.");
        }
        final List<String> errors = SetupCatalog.validate(current.gearCount(), values, current.ranges());
        if (!errors.isEmpty()) {
            throw new InvalidSetupException(errors);
        }
        return persist(carOrdinal, performanceClass, Optional.of(current), new CarSetup(current.gearCount(), values, new HashMap<>(current.ranges()), clock.instant()));
    }

    /** Apaga o setup: o carro volta ao rascunho e a coleta para (nenhuma sessão conta até aceitar de novo). */
    @Transactional
    public void delete(final int carOrdinal, final PerformanceClass performanceClass) {
        if (!store.delete(carOrdinal, performanceClass)) {
            throw new ResourceNotFoundException("Nenhum setup cadastrado para o carro " + carOrdinal);
        }
    }

    private SetupDTO persist(final int carOrdinal, final PerformanceClass performanceClass, final Optional<CarSetup> previous, final CarSetup next) {
        if (previous.isPresent() && sameSetup(previous.get(), next)) {
            return toDto(previous.get());
        }
        tuningService.resetCollection(carOrdinal, performanceClass);   // antes de gravar: a foto do histórico usa os valores antigos
        store.save(carOrdinal, performanceClass, next);
        return toDto(next);
    }

    private static boolean sameSetup(final CarSetup a, final CarSetup b) {
        return a.gearCount() == b.gearCount() && a.values().equals(b.values()) && a.ranges().equals(b.ranges());
    }

    private static SetupDTO toDto(final CarSetup setup) {
        return new SetupDTO(true, setup.gearCount(), setup.values(), setup.ranges(), fields(setup.gearCount()), setup.confirmedAt());
    }

    private static SetupDTO draft() {
        return new SetupDTO(false, DEFAULT_GEAR_COUNT, TuningBaseline.defaultValues(), Map.of(), fields(DEFAULT_GEAR_COUNT), null);
    }

    private static List<SetupDTO.FieldDTO> fields(final int gearCount) {
        return SetupCatalog.fields(gearCount).stream()
                .map(f -> new SetupDTO.FieldDTO(f.key(), f.guide(), f.label(), f.axle(), f.unit(), f.min(), f.max(), f.perCar())).toList();
    }
}
