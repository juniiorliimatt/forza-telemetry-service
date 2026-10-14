package br.com.forza.models.dto;

import br.com.forza.tuning.SetupCatalog.Range;
import java.util.Map;

/** Corpo do PUT do setup: quantidade de marchas, um valor por campo e o intervalo do slider de molas e aerodinâmica. Validado contra o catálogo. */
public record SetupRequest(Integer gearCount, Map<String, Double> values, Map<String, Range> ranges) {
}
