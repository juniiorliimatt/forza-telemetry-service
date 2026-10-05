package br.com.forza.tuning;

import br.com.forza.models.dto.TuningRecommendationDTO.TuningSetupGroupDTO;
import br.com.forza.models.dto.TuningRecommendationDTO.TuningSetupItemDTO;
import java.util.List;

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
