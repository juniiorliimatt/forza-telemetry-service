package br.com.forza.tuning;

import br.com.forza.models.dto.TuningRecommendationDTO.TuningSetupGroupDTO;
import br.com.forza.models.dto.TuningRecommendationDTO.TuningSetupItemDTO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuração inicial do desenvolvedor: a receita que ele aplica em qualquer carro antes de afinar. Ele mesmo diz que "não
 * faz sentido na vida real, mas no jogo funciona" e deixa o carro bom logo de cara. É dado dele (não deduzido da telemetria):
 * serve de <b>recomendação inicial</b>, o ponto de partida até o primeiro ciclo de coleta. O advisor continua dizendo só
 * quanto mexer a partir daí. Ids dos grupos = ids das guias de tuning, na ordem do menu do jogo.
 */
public final class TuningBaseline {

    private static final List<TuningSetupGroupDTO> SETUP = List.of(
            group("pneus", "Pneus",
                    perAxle("Pressão dos pneus", "1,5 a 2,0 bar", "1,5 a 2,0 bar",
                            "1,5 em carro pequeno; 2,0 em carro grande.")),
            group("cambio", "Câmbio",
                    single("Relações", "Refaça o gráfico de marchas: 1ª marcha batendo em ~90 km/h e alinhe as barras das demais.", null)),
            group("alinhamento", "Alinhamento",
                    perAxle("Cambagem", "0,0", "0,0", "Confira a temperatura dos pneus depois de rodar e ajuste a cambagem a partir dela."),
                    perAxle("Convergência", "0,0", "0,0", null),
                    single("Caster", "7,0", "Dianteiro.")),
            group("barras", "Barras anti-rolagem",
                    perAxle("Barras estabilizadoras", "1", "65", null)),
            group("molas", "Molas",
                    perAxle("Molas", "80", "80", "No valor que o slider do jogo mostra.")),
            group("amortecimento", "Amortecimento",
                    perAxle("Rigidez de retorno", "9", "9", null),
                    perAxle("Rigidez de compressão", "3", "3", null)),
            group("aerodinamica", "Aerodinâmica",
                    perAxle("Aerodinâmica", "máximo", "75% da barra", null)),
            group("freios", "Freios",
                    single("Equilíbrio", "45%", null),
                    single("Pressão", "105%", null)),
            group("diferencial", "Diferencial",
                    perAxle("Aceleração", "100%", "100%", "Nos eixos que o carro tem (em tração só traseira ou só dianteira, o do eixo motriz)."),
                    perAxle("Desaceleração", "0%", "0%", null),
                    single("Equilíbrio", "70 a 80%", "Diferencial central, em carro de tração integral.")));

    /**
     * A receita em valores numéricos, para pré-preencher o formulário de setup (o usuário confere e aceita). Só entra o que a receita
     * define: câmbio, aerodinâmica e o intervalo das molas dependem do carro e ficam de fora, para o usuário informar. Pressão em
     * bar: 1,5 (carro pequeno) a 2,0 (grande), pré-preenchida no meio.
     */
    public static Map<String, Double> defaultValues() {
        final Map<String, Double> values = new LinkedHashMap<>();
        values.put("tire_pressure_front", 1.75);
        values.put("tire_pressure_rear", 1.75);
        values.put("camber_front", 0.0);
        values.put("camber_rear", 0.0);
        values.put("toe_front", 0.0);
        values.put("toe_rear", 0.0);
        values.put("caster", 7.0);
        values.put("arb_front", 1.0);
        values.put("arb_rear", 65.0);
        values.put("spring_front", 80.0);
        values.put("spring_rear", 80.0);
        values.put("rebound_front", 9.0);
        values.put("rebound_rear", 9.0);
        values.put("bump_front", 3.0);
        values.put("bump_rear", 3.0);
        values.put("brake_balance", 45.0);
        values.put("brake_pressure", 105.0);
        values.put("diff_accel_front", 100.0);
        values.put("diff_accel_rear", 100.0);
        values.put("diff_decel_front", 0.0);
        values.put("diff_decel_rear", 0.0);
        values.put("diff_center", 75.0);
        return values;
    }

    private TuningBaseline() {
    }

    /** A configuração inicial, imutável e igual para todo carro (o tamanho do carro só muda a pressão, dita na observação). */
    public static List<TuningSetupGroupDTO> setup() {
        return SETUP;
    }

    private static TuningSetupGroupDTO group(final String id, final String title, final TuningSetupItemDTO... items) {
        return new TuningSetupGroupDTO(id, title, List.of(items));
    }

    private static TuningSetupItemDTO perAxle(final String parameter, final String front, final String rear, final String note) {
        return new TuningSetupItemDTO(parameter, front, rear, null, note);
    }

    private static TuningSetupItemDTO single(final String parameter, final String value, final String note) {
        return new TuningSetupItemDTO(parameter, null, null, value, note);
    }
}
