package br.com.forza.controllers;

import br.com.forza.models.dto.SetupDTO;
import br.com.forza.models.dto.SetupRequest;
import br.com.forza.telemetry.PerformanceClass;
import br.com.forza.tuning.SetupService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Setup atual do carro numa classe de PI, informado pelo usuário: o app calcula os alvos do tuning em cima dele, travados nos
 * limites do jogo. A coleta de amostras só começa depois que o setup é aceito (PUT) e reinicia a cada mudança de valor.
 */
@RestController
@RequestMapping("/api/v1/tuning/cars/{carOrdinal}/{performanceClass}/setup")
public class SetupController {

    private final SetupService setupService;

    public SetupController(final SetupService setupService) {
        this.setupService = setupService;
    }

    /** O setup aceito ou, se ainda não houver, o rascunho com a receita inicial ({@code confirmed = false}); sempre com os limites de cada campo. */
    @GetMapping
    @ApiResponse(responseCode = "200", description = "Setup (ou rascunho a confirmar)", content = @Content(schema = @Schema(implementation = SetupDTO.class)))
    public ResponseEntity<SetupDTO> get(@PathVariable final int carOrdinal, @PathVariable final PerformanceClass performanceClass) {
        return ResponseEntity.ok(setupService.get(carOrdinal, performanceClass));
    }

    /** Valida, salva e aceita o setup. Mudança de valor reinicia a coleta; salvar sem mudar nada não mexe nela. */
    @PutMapping
    @ApiResponse(responseCode = "200", description = "Setup salvo", content = @Content(schema = @Schema(implementation = SetupDTO.class)))
    @ApiResponse(responseCode = "422", description = "Valor fora dos limites do jogo ou campo faltando (lista em {@code errors})", content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<SetupDTO> save(@PathVariable final int carOrdinal, @PathVariable final PerformanceClass performanceClass,
                                         @RequestBody final SetupRequest request) {
        return ResponseEntity.ok(setupService.save(carOrdinal, performanceClass, request));
    }

    /** "Apliquei": grava os alvos do ciclo atual como valores atuais e reinicia a coleta (salva antes a foto do tuning no histórico). */
    @PostMapping("/apply")
    @ApiResponse(responseCode = "200", description = "Setup atualizado com os alvos do ciclo", content = @Content(schema = @Schema(implementation = SetupDTO.class)))
    @ApiResponse(responseCode = "409", description = "Setup ainda não aceito, ou nenhum ajuste do ciclo tem valor-alvo para aplicar", content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<SetupDTO> apply(@PathVariable final int carOrdinal, @PathVariable final PerformanceClass performanceClass) {
        return ResponseEntity.ok(setupService.apply(carOrdinal, performanceClass));
    }

    /** Apaga o setup: o carro volta ao rascunho e nenhuma sessão conta até aceitar de novo. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Setup apagado")
    @ApiResponse(responseCode = "404", description = "Nenhum setup cadastrado", content = @Content(mediaType = "application/problem+json"))
    public void delete(@PathVariable final int carOrdinal, @PathVariable final PerformanceClass performanceClass) {
        setupService.delete(carOrdinal, performanceClass);
    }
}
