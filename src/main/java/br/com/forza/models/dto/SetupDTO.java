package br.com.forza.models.dto;

import br.com.forza.tuning.SetupCatalog.Range;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Setup atual do carro numa classe de PI, informado pelo usuário (o Data Out não traz). {@code confirmed = false} é o rascunho
 * pré-preenchido com a configuração inicial do desenvolvedor, ainda não salvo: a coleta só começa quando o usuário confere e
 * aceita (PUT). {@code values} traz um valor por campo de {@code fields} (menos os sem receita inicial, que o usuário informa);
 * {@code ranges} traz o mín/máx do slider de molas e aerodinâmica, que variam por carro.
 */
public record SetupDTO(boolean confirmed,
                       int gearCount,
                       Map<String, Double> values,
                       Map<String, Range> ranges,
                       List<FieldDTO> fields,
                       Instant confirmedAt) {

    /**
     * Um campo do formulário, com os limites do jogo. {@code axle}: FRONT, REAR ou NONE; {@code min}/{@code max} são nulos nos campos
     * {@code perCar} (molas e aerodinâmica), cujo intervalo o usuário informa em {@code ranges}.
     */
    public record FieldDTO(String key, String guide, String label, String axle, String unit, Double min, Double max, boolean perCar) {
    }
}
