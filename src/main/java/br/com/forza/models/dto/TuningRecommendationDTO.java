package br.com.forza.models.dto;

import java.time.Instant;
import java.util.List;

/**
 * Recomendação de tuning de um carro, calculada sobre as sessões coletadas. O Data Out não envia os
 * valores atuais do setup (nem peso/distribuição), então as sugestões são <b>direcionais</b>
 * (aumentar/reduzir X, com a evidência que a justifica) — valores absolutos exigem o peso e os
 * limites de interface do carro. {@code guides} traz TODAS as guias de tuning, mesmo as sem ajuste
 * ({@code status = OK}) ou sem sinal na telemetria ({@code NO_SIGNAL}); vazio enquanto o carro não
 * tem dados suficientes ({@code readiness.ready = false}).
 *
 * @param thisCycle até 3 ajustes prioritários, no máximo um por guia (um ciclo de teste por vez)
 * @param initialSetup configuração inicial (ponto de partida) do desenvolvedor, em valores absolutos: o front a destaca como
 *                     "recomendação inicial" enquanto o carro coleta pela primeira vez. Fotos salvas antes dela existir
 *                     (ou a lista vazia) não a trazem — o front só a mostra na tela ao vivo
 * @param setupConfirmed o usuário já conferiu e aceitou o setup atual do carro nessa classe; só então a coleta conta sessões
 *                       e as sugestões trazem valor atual e alvo. Fotos antigas não trazem (falso)
 */
public record TuningRecommendationDTO(int carOrdinal,
                                      String carName,
                                      int carClass,
                                      int performanceIndex,
                                      String performanceClass,
                                      String drivetrain,
                                      TuningReadinessDTO readiness,
                                      Instant windowFrom,
                                      Instant windowTo,
                                      Instant checkpointAt,
                                      List<TuningGuideDTO> guides,
                                      List<TuningSuggestionDTO> thisCycle,
                                      List<TuningSetupGroupDTO> initialSetup,
                                      boolean setupConfirmed) {

    /** Um grupo da configuração inicial (mesmos ids das guias de tuning: pneus, cambio, molas...). */
    public record TuningSetupGroupDTO(String id, String title, List<TuningSetupItemDTO> items) {
    }

    /**
     * Um parâmetro da configuração inicial. {@code front}/{@code rear} para o que tem um valor por eixo; {@code value}
     * para o que é um valor único ou uma receita em texto; {@code note} é o "quando/por quê". Tudo em texto, como o jogo exibe.
     */
    public record TuningSetupItemDTO(String parameter, String front, String rear, String value, String note) {
    }

    /**
     * Dados suficientes? {@code ready} = nível inicial atingido (libera as guias); {@code highConfidence} = nível de ajuste
     * fino atingido (também exige estar pronto). {@code missing} explica, em português, o que falta pro nível inicial.
     */
    public record TuningReadinessDTO(boolean ready,
                                     int sessions,
                                     int requiredSessions,
                                     long samples,
                                     long requiredSamples,
                                     boolean highConfidence,
                                     int highRequiredSessions,
                                     long highRequiredSamples,
                                     List<String> missing) {
    }

    /** {@code status}: ADJUST (há ajuste), OK (avaliada, nada a mudar) ou NO_SIGNAL (a telemetria não enxerga esta guia). */
    public record TuningGuideDTO(String id,
                                 String title,
                                 String status,
                                 String summary,
                                 List<String> notes,
                                 List<TuningSuggestionDTO> suggestions) {
    }

    /**
     * @param axle FRONT, REAR, BOTH ou NONE
     * @param direction INCREASE ou DECREASE (do valor numérico que o jogo exibe)
     * @param amount tamanho do passo deste ciclo (não o valor final: o Data Out não traz o setup atual), na unidade {@code unit}
     *               — nulo em fotos salvas antes dessa informação existir
     * @param unit unidade que o jogo mostra (bar, °, pontos, cm, pontos percentuais, pontos percentuais do curso do slider, na relação)
     * @param magnitude SMALL, MEDIUM ou LARGE, conforme a severidade do sintoma
     * @param setupKey campo do setup do carro que esta sugestão mexe ({@code tire_pressure_front}...), ou nulo se não houver
     * @param current valor atual informado pelo usuário, nulo sem setup confirmado ou sem campo correspondente
     * @param target valor-alvo: {@code current ± amount}, travado entre o mínimo e o máximo do jogo; nulo como {@code current}
     * @param atLimit o valor atual já está no limite na direção sugerida (não dá para ir além)
     */
    public record TuningSuggestionDTO(int priority,
                                      boolean thisCycle,
                                      String guide,
                                      String parameter,
                                      String axle,
                                      String direction,
                                      String rationale,
                                      String evidence,
                                      Double amount,
                                      String unit,
                                      String magnitude,
                                      String setupKey,
                                      Double current,
                                      Double target,
                                      boolean atLimit) {

        /** Sugestão sem valor atual/alvo (setup não confirmado ou parâmetro sem campo). */
        public TuningSuggestionDTO(final int priority, final boolean thisCycle, final String guide, final String parameter, final String axle,
                                   final String direction, final String rationale, final String evidence, final Double amount, final String unit,
                                   final String magnitude) {
            this(priority, thisCycle, guide, parameter, axle, direction, rationale, evidence, amount, unit, magnitude, null, null, null, false);
        }

        public TuningSuggestionDTO withTarget(final String key, final double currentValue, final double targetValue, final boolean limit) {
            return new TuningSuggestionDTO(priority, thisCycle, guide, parameter, axle, direction, rationale, evidence, amount, unit, magnitude,
                    key, currentValue, targetValue, limit);
        }

        public TuningSuggestionDTO withCycle(final int newPriority, final boolean inCycle) {
            return new TuningSuggestionDTO(newPriority, inCycle, guide, parameter, axle, direction, rationale, evidence, amount, unit, magnitude,
                    setupKey, current, target, atLimit);
        }
    }
}
