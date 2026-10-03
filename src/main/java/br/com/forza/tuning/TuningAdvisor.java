package br.com.forza.tuning;

import br.com.forza.models.dto.TuningRecommendationDTO.TuningGuideDTO;
import br.com.forza.models.dto.TuningRecommendationDTO.TuningSuggestionDTO;
import br.com.forza.tuning.TuningAggregate.Phase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Regras de diagnóstico → ajuste, uma por sintoma, agrupadas nas guias de tuning do jogo (Pneus, Câmbio,
 * Alinhamento, Barras, Molas, Amortecimento, Aerodinâmica, Freios, Diferencial). A matriz sintoma→correção
 * e os limiares seguem a skill {@code forza-tuning-engineer}: bottoming &gt; 3%, pneus 180–200 °F (aceitável
 * 170–210) e diferença entre eixos &gt; 15 °F, limitador na última marcha, travamento de roda em frenagem etc.
 * <p>
 * Só <b>direções</b> (aumentar/reduzir): o Data Out não traz os valores atuais do setup nem peso/distribuição.
 * Uma regra só dispara com evidência mínima (amostras) e todas as guias são sempre devolvidas — uma guia
 * sem ajuste vem como {@code OK}, e a que a telemetria não enxerga (aerodinâmica) como {@code NO_SIGNAL}.
 * No máximo 3 ajustes por ciclo de teste, no máximo um por guia.
 */
@Component
public class TuningAdvisor {

    static final int MIN_PHASE_SAMPLES = 150;
    static final int MIN_BRAKING_SAMPLES = 100;
    static final int MIN_TRACTION_SAMPLES = 150;
    static final double BALANCE_GAP_PTS = 20.0;
    static final double BOTTOMING_PCT = 3.0;
    static final double LOCK_PCT = 5.0;
    static final double SPIN_PCT = 15.0;
    static final double TIRE_COLD_F = 170.0;
    static final double TIRE_HOT_F = 210.0;
    static final double AXLE_DIFF_F = 15.0;
    static final double TOP_GEAR_LIMITER_PCT = 5.0;
    static final double MID_GEAR_LIMITER_PCT = 10.0;
    static final int MAX_PER_CYCLE = 3;

    private static final int FWD = 0;

    /** Resultado: todas as guias (com prioridade) e os ajustes do ciclo atual. */
    public record Advice(List<TuningGuideDTO> guides, List<TuningSuggestionDTO> thisCycle) {
    }

    private record Draft(String guide, String parameter, String axle, String direction, String rationale, String evidence, double severity) {
    }

    private record GuideDraft(String id, String title, boolean evaluated, String okSummary, String noSignalSummary, List<String> notes, List<Draft> drafts) {
    }

    public Advice advise(final TuningAggregate a, final int drivetrain) {
        final List<GuideDraft> drafts = List.of(
                pneus(a), cambio(a), alinhamento(a), barras(a), molas(a), amortecimento(a), aerodinamica(), freios(a), diferencial(a, drivetrain));

        final List<Draft> all = drafts.stream().flatMap(g -> g.drafts().stream()).sorted(Comparator.comparingDouble(Draft::severity).reversed()).toList();
        final List<Draft> cycle = new ArrayList<>();
        final Set<String> usedGuides = new HashSet<>();
        for (final Draft draft : all) {
            if (cycle.size() < MAX_PER_CYCLE && usedGuides.add(draft.guide())) {
                cycle.add(draft);
            }
        }
        final Map<Draft, Integer> priority = new LinkedHashMap<>();
        int next = 1;
        for (final Draft draft : cycle) {
            priority.put(draft, next++);
        }
        for (final Draft draft : all) {
            if (!priority.containsKey(draft)) {
                priority.put(draft, next++);
            }
        }

        final List<TuningGuideDTO> guides = drafts.stream().map(g -> {
            final List<TuningSuggestionDTO> suggestions = g.drafts().stream()
                    .sorted(Comparator.comparingInt(priority::get))
                    .map(d -> toDto(d, priority.get(d), cycle.contains(d)))
                    .toList();
            final String status = !suggestions.isEmpty() ? "ADJUST" : g.evaluated() ? "OK" : "NO_SIGNAL";
            final String summary = !suggestions.isEmpty() ? suggestions.size() + (suggestions.size() == 1 ? " ajuste sugerido" : " ajustes sugeridos")
                    : g.evaluated() ? g.okSummary() : g.noSignalSummary();
            return new TuningGuideDTO(g.id(), g.title(), status, summary, g.notes(), suggestions);
        }).toList();

        return new Advice(guides, cycle.stream().map(d -> toDto(d, priority.get(d), true)).toList());
    }

    private static TuningSuggestionDTO toDto(final Draft d, final int priority, final boolean thisCycle) {
        return new TuningSuggestionDTO(priority, thisCycle, d.guide(), d.parameter(), d.axle(), d.direction(), d.rationale(), d.evidence());
    }

    // ---------------------------------------------------------------- guias

    private GuideDraft pneus(final TuningAggregate a) {
        final List<Draft> out = new ArrayList<>();
        final boolean evaluated = a.tireTempF().size() == 4;
        if (evaluated) {
            final double front = (a.tireTempF().get("FL") + a.tireTempF().get("FR")) / 2.0;
            final double rear = (a.tireTempF().get("RL") + a.tireTempF().get("RR")) / 2.0;
            final boolean frontFlagged = axleTemp(out, "pneus", "FRONT", "dianteiro", front);
            final boolean rearFlagged = axleTemp(out, "pneus", "REAR", "traseiro", rear);
            final double diff = Math.abs(front - rear);
            if (diff > AXLE_DIFF_F && !frontFlagged && !rearFlagged) {
                final boolean frontHotter = front > rear;
                out.add(new Draft("pneus", "Pressão dos pneus " + (frontHotter ? "dianteiros" : "traseiros"), frontHotter ? "FRONT" : "REAR", "DECREASE",
                        "O eixo mais quente trabalha acima do outro; reduzir a pressão dele equilibra a temperatura entre os eixos.",
                        "Diferença entre eixos: " + n(diff) + " °F (dianteiro " + n(front) + " °F, traseiro " + n(rear) + " °F; limite " + n(AXLE_DIFF_F) + " °F)",
                        1.0 + (diff - AXLE_DIFF_F) / AXLE_DIFF_F));
            }
        }
        return new GuideDraft("pneus", "Pneus", evaluated, "Temperaturas dentro da janela de 170–210 °F e equilibradas entre os eixos.",
                "Sem temperatura de pneus nas sessões.",
                List.of("O Data Out traz uma temperatura por pneu (não a face interna/externa), então a pressão é avaliada, a cambagem não."), out);
    }

    private static boolean axleTemp(final List<Draft> out, final String guide, final String axle, final String label, final double temp) {
        if (temp < TIRE_COLD_F) {
            out.add(new Draft(guide, "Pressão dos pneus " + (axle.equals("FRONT") ? "dianteiros" : "traseiros"), axle, "INCREASE",
                    "Pneu frio não chega à janela de aderência (180–200 °F); mais pressão aquece o eixo.",
                    "Temperatura média do eixo " + label + ": " + n(temp) + " °F (mínimo aceitável " + n(TIRE_COLD_F) + " °F)",
                    1.0 + (TIRE_COLD_F - temp) / 20.0));
            return true;
        }
        if (temp > TIRE_HOT_F) {
            out.add(new Draft(guide, "Pressão dos pneus " + (axle.equals("FRONT") ? "dianteiros" : "traseiros"), axle, "DECREASE",
                    "Pneu superaquecido perde aderência e desgasta; menos pressão reduz a temperatura do eixo.",
                    "Temperatura média do eixo " + label + ": " + n(temp) + " °F (máximo aceitável " + n(TIRE_HOT_F) + " °F)",
                    1.0 + (temp - TIRE_HOT_F) / 20.0));
            return true;
        }
        return false;
    }

    private GuideDraft cambio(final TuningAggregate a) {
        final List<Draft> out = new ArrayList<>();
        final boolean evaluated = !a.limiterPctByGear().isEmpty();
        if (evaluated) {
            final int top = a.limiterPctByGear().keySet().stream().mapToInt(Integer::parseInt).max().orElse(0);
            final double topLimiter = a.limiterPctByGear().getOrDefault(String.valueOf(top), 0.0);
            if (topLimiter > TOP_GEAR_LIMITER_PCT) {
                out.add(new Draft("cambio", "Relação final (transmissão final)", "NONE", "DECREASE",
                        "Bater no limitador com acelerador na última marcha desperdiça tempo; uma final mais longa (valor numérico menor) só deixa o limitador para o fim da reta.",
                        topGearEvidence(top, topLimiter), 1.0 + (topLimiter - TOP_GEAR_LIMITER_PCT) / TOP_GEAR_LIMITER_PCT));
            }
            a.limiterPctByGear().entrySet().stream()
                    .filter(e -> Integer.parseInt(e.getKey()) != top && e.getValue() > MID_GEAR_LIMITER_PCT)
                    .max(Map.Entry.comparingByValue())
                    .ifPresent(e -> out.add(new Draft("cambio", "Relação da " + e.getKey() + "ª marcha", "NONE", "DECREASE",
                            "A marcha passa muito tempo no limitador; uma relação mais longa (ou troca mais cedo) aproveita melhor a faixa de torque. Exige transmissão de corrida.",
                            "Limitador com acelerador na " + e.getKey() + "ª marcha: " + n(e.getValue()) + "% do tempo nela (máx. " + n(MID_GEAR_LIMITER_PCT) + "%)",
                            1.0 + (e.getValue() - MID_GEAR_LIMITER_PCT) / MID_GEAR_LIMITER_PCT)));
        }
        return new GuideDraft("cambio", "Câmbio", evaluated, "O limitador raramente é atingido com o acelerador pressionado: relação final adequada.",
                "Sem dados de marchas nas sessões.",
                List.of("Dado de relação por marcha só aparece como ajuste fino com transmissão de corrida instalada."), out);
    }

    private static String topGearEvidence(final int gear, final double pct) {
        return "Limitador com acelerador na " + gear + "ª marcha (a mais longa): " + n(pct) + "% do tempo nela (máx. " + n(TOP_GEAR_LIMITER_PCT) + "%)";
    }

    private GuideDraft alinhamento(final TuningAggregate a) {
        final List<Draft> out = new ArrayList<>();
        final boolean evaluated = evaluable(a.entry()) || evaluable(a.mid()) || evaluable(a.exit());
        if (understeer(a.mid())) {
            out.add(new Draft("alinhamento", "Cambagem dianteira (mais negativa)", "FRONT", "DECREASE",
                    "Subesterço no meio da curva: mais cambagem negativa na frente aumenta a área de contato com o pneu externo carregado.",
                    balanceEvidence("meio de curva", "subesterço", a.mid().understeerPct(), a.mid().samples()), severity(a.mid().understeerPct() - a.mid().oversteerPct())));
        }
        if (understeer(a.entry())) {
            out.add(new Draft("alinhamento", "Convergência dianteira (mais aberta / toe-out)", "FRONT", "DECREASE",
                    "Subesterço na entrada (freando): convergência dianteira aberta ajuda a esterçar o carro para dentro.",
                    balanceEvidence("entrada de curva", "subesterço", a.entry().understeerPct(), a.entry().samples()), severity(a.entry().understeerPct() - a.entry().oversteerPct())));
        }
        return new GuideDraft("alinhamento", "Alinhamento", evaluated, "Balanço em curva equilibrado: alinhamento atual não precisa mudar.",
                "Poucas amostras de curva para avaliar o alinhamento.",
                List.of("Cambagem ideal depende da temperatura da face interna/externa do pneu, que o Data Out não envia: aqui só entram sinais de balanço em curva.",
                        "Caster não tem sinal na telemetria."), out);
    }

    private GuideDraft barras(final TuningAggregate a) {
        final List<Draft> out = new ArrayList<>();
        final boolean evaluated = evaluable(a.entry()) || evaluable(a.mid()) || evaluable(a.exit());
        if (understeer(a.mid())) {
            out.add(new Draft("barras", "Barra dianteira (anti-rolagem)", "FRONT", "DECREASE",
                    "Subesterço no meio da curva: barra dianteira mais macia devolve aderência ao eixo dianteiro (alternativa: barra traseira mais dura).",
                    balanceEvidence("meio de curva", "subesterço", a.mid().understeerPct(), a.mid().samples()), severity(a.mid().understeerPct() - a.mid().oversteerPct())));
        }
        if (understeer(a.exit())) {
            out.add(new Draft("barras", "Barra traseira (anti-rolagem)", "REAR", "INCREASE",
                    "Subesterço na saída: barra traseira mais dura transfere carga para fora e ajuda o carro a rotacionar.",
                    balanceEvidence("saída de curva", "subesterço", a.exit().understeerPct(), a.exit().samples()), severity(a.exit().understeerPct() - a.exit().oversteerPct())));
        }
        final Draft rearSoften = rearSoften(a);
        if (rearSoften != null) {
            out.add(rearSoften);
        }
        return new GuideDraft("barras", "Barras anti-rolagem", evaluated, "Balanço em curva equilibrado: barras atuais atendem.",
                "Poucas amostras de curva para avaliar as barras.", List.of(), dedupe(out));
    }

    private static Draft rearSoften(final TuningAggregate a) {
        Draft best = null;
        if (oversteer(a.entry())) {
            best = new Draft("barras", "Barra traseira (anti-rolagem)", "REAR", "DECREASE",
                    "Sobresterço na entrada: barra traseira mais macia estabiliza a traseira ao frear e esterçar.",
                    balanceEvidence("entrada de curva", "sobresterço", a.entry().oversteerPct(), a.entry().samples()), severity(a.entry().oversteerPct() - a.entry().understeerPct()));
        }
        if (oversteer(a.exit())) {
            final Draft candidate = new Draft("barras", "Barra traseira (anti-rolagem)", "REAR", "DECREASE",
                    "Sobresterço na saída: barra traseira mais macia dá mais tração e menos rotação ao acelerar.",
                    balanceEvidence("saída de curva", "sobresterço", a.exit().oversteerPct(), a.exit().samples()), severity(a.exit().oversteerPct() - a.exit().understeerPct()));
            if (best == null || candidate.severity() > best.severity()) {
                best = candidate;
            }
        }
        return best;
    }

    private GuideDraft molas(final TuningAggregate a) {
        final List<Draft> out = new ArrayList<>();
        final boolean evaluated = a.bottomingPct().size() == 4 || evaluable(a.entry()) || evaluable(a.exit());
        bottomingDrafts(a).forEach(b -> out.add(new Draft("molas", "Altura do solo " + axleWord(b.axle()), b.axle(), "INCREASE",
                "A suspensão está batendo no limite de curso; mais altura (ou mola mais dura) devolve curso útil.", b.evidence(), b.severity())));
        if (understeer(a.entry())) {
            out.add(new Draft("molas", "Mola dianteira", "FRONT", "DECREASE",
                    "Subesterço na entrada: mola dianteira mais macia aumenta a transferência de carga e a aderência na frente ao frear.",
                    balanceEvidence("entrada de curva", "subesterço", a.entry().understeerPct(), a.entry().samples()), severity(a.entry().understeerPct() - a.entry().oversteerPct())));
        }
        if (oversteer(a.exit())) {
            out.add(new Draft("molas", "Mola traseira", "REAR", "DECREASE",
                    "Sobresterço na saída: mola traseira mais macia aumenta a tração ao acelerar.",
                    balanceEvidence("saída de curva", "sobresterço", a.exit().oversteerPct(), a.exit().samples()), severity(a.exit().oversteerPct() - a.exit().understeerPct())));
        }
        return new GuideDraft("molas", "Molas", evaluated, "Sem batida de suspensão relevante e balanço em curva equilibrado.",
                "Sem dados de suspensão nas sessões.", List.of("Valores absolutos de mola/altura dependem do peso e da distribuição do carro (tela de ajuste)."), out);
    }

    private GuideDraft amortecimento(final TuningAggregate a) {
        final List<Draft> out = new ArrayList<>();
        final boolean evaluated = a.bottomingPct().size() == 4;
        bottomingDrafts(a).forEach(b -> out.add(new Draft("amortecimento", "Rigidez de compressão " + axleWord(b.axle()), b.axle(), "INCREASE",
                "Bottoming: mais compressão segura o curso nas ondulações e frenagens (mantenha a compressão em 50–60% do retorno).", b.evidence(), b.severity())));
        return new GuideDraft("amortecimento", "Amortecimento", evaluated, "Curso de suspensão dentro do limite: amortecimento atual atende.",
                "Sem dados de suspensão nas sessões.",
                List.of("O Data Out não mede instabilidade em zebra/ondulação: só o bottoming vira ajuste aqui."), out);
    }

    private static List<Draft> bottomingDrafts(final TuningAggregate a) {
        final List<Draft> out = new ArrayList<>();
        if (a.bottomingPct().size() < 4) {
            return out;
        }
        for (final String axle : List.of("FRONT", "REAR")) {
            final String left = axle.equals("FRONT") ? "FL" : "RL";
            final String right = axle.equals("FRONT") ? "FR" : "RR";
            final double worst = Math.max(a.bottomingPct().get(left), a.bottomingPct().get(right));
            final String wheel = a.bottomingPct().get(left) >= a.bottomingPct().get(right) ? left : right;
            if (worst > BOTTOMING_PCT) {
                out.add(new Draft("", "", axle, "INCREASE", "",
                        "Suspensão " + (axle.equals("FRONT") ? "dianteira" : "traseira") + " no fundo de curso em " + n(worst) + "% das amostras (roda " + wheel + "; limite " + n(BOTTOMING_PCT) + "%)",
                        worst / BOTTOMING_PCT));
            }
        }
        return out;
    }

    private GuideDraft aerodinamica() {
        return new GuideDraft("aerodinamica", "Aerodinâmica", false, "", "A telemetria não separa comportamento em alta velocidade: sem recomendação automática.",
                List.of("Regra prática: se sobra velocidade final e falta aderência em alta, comece por mais downforce traseiro.",
                        "Só existe ajuste se o carro tiver aerofólios ajustáveis instalados."), List.of());
    }

    private GuideDraft freios(final TuningAggregate a) {
        final List<Draft> out = new ArrayList<>();
        final boolean evaluated = a.brakingSamples() >= MIN_BRAKING_SAMPLES;
        if (evaluated) {
            final boolean front = a.frontLockPct() > LOCK_PCT;
            if (front) {
                out.add(new Draft("freios", "Pressão de freio", "BOTH", "DECREASE",
                        "Rodas dianteiras travando sob frenagem forte: menos pressão (ou equilíbrio mais para trás) evita o travamento.",
                        "Dianteira travando em " + n(a.frontLockPct()) + "% das amostras de frenagem forte (n=" + a.brakingSamples() + "; limite " + n(LOCK_PCT) + "%)",
                        a.frontLockPct() / LOCK_PCT));
            }
            if (a.rearLockPct() > LOCK_PCT && !front) {
                out.add(new Draft("freios", "Equilíbrio de freio (mais para a dianteira)", "FRONT", "INCREASE",
                        "Traseira travando sob frenagem forte: mover o equilíbrio para a dianteira estabiliza a frenagem.",
                        "Traseira travando em " + n(a.rearLockPct()) + "% das amostras de frenagem forte (n=" + a.brakingSamples() + "; limite " + n(LOCK_PCT) + "%)",
                        a.rearLockPct() / LOCK_PCT));
            }
        }
        return new GuideDraft("freios", "Freios", evaluated, "Sem travamento de rodas relevante nas frenagens fortes.",
                "Poucas frenagens fortes registradas para avaliar os freios.",
                List.of("Com ABS ligado o travamento quase não aparece; o sinal é mais forte com ABS desligado."), out);
    }

    private GuideDraft diferencial(final TuningAggregate a, final int drivetrain) {
        final List<Draft> out = new ArrayList<>();
        final boolean evaluated = a.tractionSamples() >= MIN_TRACTION_SAMPLES || evaluable(a.entry()) || evaluable(a.exit());
        final String axle = drivetrain == FWD ? "FRONT" : "REAR";
        final String axleName = drivetrain == FWD ? "dianteiro" : "traseiro";
        if (a.tractionSamples() >= MIN_TRACTION_SAMPLES && a.spinPct() > SPIN_PCT) {
            final String worst = a.spinPctByGear().entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> "; pior na " + e.getKey() + "ª marcha (" + n(e.getValue()) + "%)").orElse("");
            out.add(new Draft("diferencial", "Diferencial " + axleName + " — aceleração", axle, "DECREASE",
                    "Patinagem das rodas motrizes ao acelerar: diferencial menos travado distribui melhor o torque.",
                    "Rodas motrizes patinando em " + n(a.spinPct()) + "% das amostras em aceleração (n=" + a.tractionSamples() + "; limite " + n(SPIN_PCT) + "%)" + worst,
                    a.spinPct() / SPIN_PCT));
        }
        if (understeer(a.entry())) {
            out.add(new Draft("diferencial", "Diferencial " + axleName + " — desaceleração", axle, "DECREASE",
                    "Subesterço na entrada: menos travamento na desaceleração deixa o eixo motriz girar livre ao esterçar.",
                    balanceEvidence("entrada de curva", "subesterço", a.entry().understeerPct(), a.entry().samples()), severity(a.entry().understeerPct() - a.entry().oversteerPct())));
        }
        if (oversteer(a.entry())) {
            out.add(new Draft("diferencial", "Diferencial " + axleName + " — desaceleração", axle, "INCREASE",
                    "Sobresterço na entrada: mais travamento na desaceleração estabiliza o eixo motriz ao tirar o pé.",
                    balanceEvidence("entrada de curva", "sobresterço", a.entry().oversteerPct(), a.entry().samples()), severity(a.entry().oversteerPct() - a.entry().understeerPct())));
        }
        if (drivetrain == FWD || drivetrain == 2) {
            if (understeer(a.exit())) {
                out.add(new Draft("diferencial", "Diferencial dianteiro — aceleração", "FRONT", "DECREASE",
                        "Subesterço na saída (tração dianteira): menos travamento na aceleração reduz o arrasto do eixo dianteiro.",
                        balanceEvidence("saída de curva", "subesterço", a.exit().understeerPct(), a.exit().samples()), severity(a.exit().understeerPct() - a.exit().oversteerPct())));
            }
        }
        if (drivetrain == 1 && oversteer(a.exit())) {
            out.add(new Draft("diferencial", "Diferencial traseiro — aceleração", "REAR", "DECREASE",
                    "Sobresterço na saída (tração traseira): diferencial menos travado reduz a rotação ao acelerar.",
                    balanceEvidence("saída de curva", "sobresterço", a.exit().oversteerPct(), a.exit().samples()), severity(a.exit().oversteerPct() - a.exit().understeerPct())));
        }
        return new GuideDraft("diferencial", "Diferencial", evaluated, "Tração e balanço de entrada/saída equilibrados: diferencial atual atende.",
                "Poucas amostras de aceleração/curva para avaliar o diferencial.",
                List.of("Em AWD, o equilíbrio do diferencial central não tem sinal direto na telemetria."), dedupe(out));
    }

    // ---------------------------------------------------------------- utilitários

    private static boolean evaluable(final Phase p) {
        return p.samples() >= MIN_PHASE_SAMPLES;
    }

    private static boolean understeer(final Phase p) {
        return evaluable(p) && p.understeerPct() - p.oversteerPct() >= BALANCE_GAP_PTS;
    }

    private static boolean oversteer(final Phase p) {
        return evaluable(p) && p.oversteerPct() - p.understeerPct() >= BALANCE_GAP_PTS;
    }

    private static double severity(final double gapPts) {
        return gapPts / BALANCE_GAP_PTS;
    }

    private static String balanceEvidence(final String phase, final String kind, final double pct, final long samples) {
        return n(pct) + "% das amostras de " + phase + " em " + kind + " (n=" + samples + ")";
    }

    private static String axleWord(final String axle) {
        return axle.equals("FRONT") ? "dianteira" : "traseira";
    }

    /** Mesmo parâmetro+direção vindo de duas regras: fica a de maior severidade. */
    private static List<Draft> dedupe(final List<Draft> drafts) {
        final Map<String, Draft> byKey = new LinkedHashMap<>();
        for (final Draft d : drafts) {
            byKey.merge(d.parameter() + "|" + d.axle(), d, (x, y) -> y.severity() > x.severity() ? y : x);
        }
        return new ArrayList<>(byKey.values());
    }

    private static String n(final double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
