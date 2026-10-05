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
                                      List<TuningSetupGroupDTO> initialSetup) {

    /** Um grupo da configuração inicial (mesmos ids das guias de tuning: pneus, cambio, molas...). */
    public record TuningSetupGroupDTO(String id, String title, List<TuningSetupItemDTO> items) {
    }

    /**
     * Um parâmetro da configuração inicial. {@code front}/{@code rear} para o que tem um valor por eixo; {@code value}
     * para o que é um valor único ou uma receita em texto; {@code note} é o "quando/por quê". Tudo em texto, como o jogo exibe.
     */
    public record TuningSetupItemDTO(String parameter, String front, String rear, String value, String note) {
    }

    /** Dados suficientes? {@code missing} explica o que falta, em português. */
    public record TuningReadinessDTO(boolean ready,
                                     int sessions,
                                     int requiredSessions,
                                     long samples,
                                     long requiredSamples,
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
                                      String magnitude) {
    }
}
