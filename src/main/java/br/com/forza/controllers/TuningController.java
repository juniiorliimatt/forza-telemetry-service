package br.com.forza.controllers;

import br.com.forza.models.dto.TuningCarDTO;
import br.com.forza.models.dto.TuningHistoryDTO;
import br.com.forza.models.dto.TuningHistoryItemDTO;
import br.com.forza.models.dto.TuningRecommendationDTO;
import br.com.forza.telemetry.PerformanceClass;
import br.com.forza.tuning.TuningService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recomendação de tuning (família Horizon: FH4/FH5/FH6, que compartilham o pacote do Data Out). Qualquer
 * usuário autenticado vê todos os carros, como nas sessões.
 */
@RestController
@RequestMapping("/api/v1/tuning")
public class TuningController {

    private final TuningService tuningService;

    public TuningController(final TuningService tuningService) {
        this.tuningService = tuningService;
    }

    /** Carros com sessões coletadas e o progresso até poder recomendar (mais recente primeiro). */
    @GetMapping("/cars")
    public ResponseEntity<List<TuningCarDTO>> cars() {
        return ResponseEntity.ok(tuningService.cars());
    }

    /**
     * Recomendação do carro numa classe de PI (cada classe é uma build): todas as guias de tuning e os (até 3)
     * ajustes do ciclo atual — ou o que falta pra gerar.
     */
    @GetMapping("/cars/{carOrdinal}/{performanceClass}")
    @ApiResponse(responseCode = "200", description = "Recomendação (ou o que falta pra gerá-la)",
            content = @Content(schema = @Schema(implementation = TuningRecommendationDTO.class)))
    @ApiResponse(responseCode = "404", description = "Nenhuma sessão coletada para o carro nessa classe", content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<TuningRecommendationDTO> recommendation(@PathVariable final int carOrdinal, @PathVariable final PerformanceClass performanceClass) {
        return ResponseEntity.ok(tuningService.recommendation(carOrdinal, performanceClass));
    }

    /** Tunings já feitos (fotos gravadas ao reiniciar a coleta de um carro), do mais recente para o mais antigo. */
    @GetMapping("/history")
    public ResponseEntity<List<TuningHistoryItemDTO>> history() {
        return ResponseEntity.ok(tuningService.history());
    }

    /** Um tuning salvo, com todas as guias e os ajustes do ciclo como estavam quando foi gravado. */
    @GetMapping("/history/{id}")
    @ApiResponse(responseCode = "200", description = "Tuning salvo", content = @Content(schema = @Schema(implementation = TuningHistoryDTO.class)))
    @ApiResponse(responseCode = "404", description = "Tuning salvo não encontrado", content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<TuningHistoryDTO> historyEntry(@PathVariable final UUID id) {
        return ResponseEntity.ok(tuningService.historyEntry(id));
    }

    /**
     * Reinicia a coleta do carro nessa classe de PI (use depois de aplicar ajustes no jogo): só sessões novas passam a
     * contar. Se a recomendação já estava pronta, ela é salva no histórico antes. Não afeta as outras classes do carro.
     */
    @PostMapping("/cars/{carOrdinal}/{performanceClass}/checkpoint")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetCollection(@PathVariable final int carOrdinal, @PathVariable final PerformanceClass performanceClass) {
        tuningService.resetCollection(carOrdinal, performanceClass);
    }
}
