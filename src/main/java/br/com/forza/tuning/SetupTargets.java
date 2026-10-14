package br.com.forza.tuning;

import br.com.forza.models.dto.TuningRecommendationDTO.TuningGuideDTO;
import br.com.forza.models.dto.TuningRecommendationDTO.TuningSuggestionDTO;
import br.com.forza.tuning.SetupCatalog.Field;
import br.com.forza.tuning.SetupCatalog.Range;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Com o setup confirmado, transforma o "mexa ±X" do advisor em valor atual → alvo: {@code alvo = atual ± passo}, travado entre
 * o mínimo e o máximo do jogo (ou do slider do carro, em molas e aerodinâmica). Sugestão que já está no limite na direção
 * sugerida fica marcada {@code atLimit} e sai do ciclo — entra no lugar a próxima melhor (uma por guia, até 3).
 */
final class SetupTargets {

    private static final String SLIDER_PERCENT_UNIT = "pontos percentuais do curso do slider";

    private SetupTargets() {
    }

    static TuningAdvisor.Advice apply(final TuningAdvisor.Advice advice, final Map<String, Double> values, final Map<String, Range> ranges) {
        final List<TuningSuggestionDTO> enriched = new ArrayList<>();
        final Map<TuningSuggestionDTO, TuningSuggestionDTO> byOriginal = new LinkedHashMap<>();
        for (final TuningGuideDTO guide : advice.guides()) {
            for (final TuningSuggestionDTO s : guide.suggestions()) {
                final TuningSuggestionDTO e = enrich(s, values, ranges);
                byOriginal.put(s, e);
                enriched.add(e);
            }
        }

        // Ciclo: melhor prioridade primeiro, uma por guia, só as que ainda têm para onde ir; no máximo o mesmo tamanho de antes.
        final int size = Math.max(advice.thisCycle().size(), TuningAdvisor.MAX_PER_CYCLE);
        final List<TuningSuggestionDTO> cycle = new ArrayList<>();
        final Set<String> usedGuides = new HashSet<>();
        for (final TuningSuggestionDTO s : enriched.stream().sorted(Comparator.comparingInt(TuningSuggestionDTO::priority)).toList()) {
            if (cycle.size() < size && !s.atLimit() && usedGuides.add(s.guide())) {
                cycle.add(s);
            }
        }

        final List<TuningSuggestionDTO> orderedCycle = new ArrayList<>();
        for (int i = 0; i < cycle.size(); i++) {
            orderedCycle.add(cycle.get(i).withCycle(i + 1, true));
        }
        final Map<String, TuningSuggestionDTO> cycleById = new LinkedHashMap<>();
        orderedCycle.forEach(s -> cycleById.put(identity(s), s));

        final List<TuningGuideDTO> guides = advice.guides().stream().map(g -> new TuningGuideDTO(g.id(), g.title(), g.status(), g.summary(), g.notes(),
                g.suggestions().stream().map(s -> {
                    final TuningSuggestionDTO e = byOriginal.get(s);
                    final TuningSuggestionDTO inCycle = cycleById.get(identity(e));
                    return inCycle != null ? inCycle : e.withCycle(e.priority() + orderedCycle.size(), false);
                }).toList())).toList();
        return new TuningAdvisor.Advice(guides, orderedCycle);
    }

    private static String identity(final TuningSuggestionDTO s) {
        return s.guide() + "|" + s.parameter() + "|" + s.axle();
    }

    private static TuningSuggestionDTO enrich(final TuningSuggestionDTO s, final Map<String, Double> values, final Map<String, Range> ranges) {
        if (s.amount() == null) {
            return s;
        }
        final Optional<String> key = SetupCatalog.keyFor(s.guide(), s.parameter(), s.axle());
        final Double current = key.map(values::get).orElse(null);
        if (key.isEmpty() || current == null) {
            return s;
        }
        final Optional<Field> field = SetupCatalog.find(key.get(), SetupCatalog.MAX_GEARS);
        final double min = field.map(f -> f.perCar() ? rangeOf(ranges, f.key(), true) : f.min()).orElse(Double.NEGATIVE_INFINITY);
        final double max = field.map(f -> f.perCar() ? rangeOf(ranges, f.key(), false) : f.max()).orElse(Double.POSITIVE_INFINITY);
        final double delta = SLIDER_PERCENT_UNIT.equals(s.unit()) ? s.amount() / 100.0 * (max - min) : s.amount();
        final double wanted = "INCREASE".equals(s.direction()) ? current + delta : current - delta;
        final double target = round(Math.max(min, Math.min(max, wanted)));
        return s.withTarget(key.get(), current, target, target == round(current));
    }

    private static double rangeOf(final Map<String, Range> ranges, final String key, final boolean min) {
        final Range range = ranges.get(key);
        return range == null ? (min ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY) : (min ? range.min() : range.max());
    }

    private static double round(final double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
