package br.com.forza.controllers;

import br.com.forza.models.dto.LapDTO;
import br.com.forza.models.dto.SampleDTO;
import br.com.forza.models.dto.SessionDTO;
import br.com.forza.models.dto.SessionPageDTO;
import br.com.forza.models.dto.TuningSummaryDTO;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import br.com.forza.services.SessionQueryService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sessões de telemetria (somente leitura — a escrita vem do listener UDP). Sessões não têm
 * dono: qualquer usuário autenticado enxerga todas (a telemetria vem do Xbox, sem identidade).
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionQueryService sessionQueryService;

    public SessionController(final SessionQueryService sessionQueryService) {
        this.sessionQueryService = sessionQueryService;
    }

    /** Mais recentes primeiro, paginação por cursor opaco ({@code nextCursor} da página anterior). */
    @GetMapping
    @ApiResponse(responseCode = "200", description = "Página de sessões",
            content = @Content(schema = @Schema(implementation = SessionPageDTO.class)))
    @ApiResponse(responseCode = "400", description = "Cursor inválido", content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<SessionPageDTO> list(@RequestParam(required = false) final String cursor,
                                               @RequestParam(defaultValue = "20") final int size) {
        return ResponseEntity.ok(sessionQueryService.list(cursor, size));
    }

    @GetMapping("/{id}")
    @ApiResponse(responseCode = "200", description = "Metadados da sessão",
            content = @Content(schema = @Schema(implementation = SessionDTO.class)))
    @ApiResponse(responseCode = "404", description = "Sessão não encontrada", content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<SessionDTO> findById(@PathVariable final UUID id) {
        return ResponseEntity.ok(sessionQueryService.get(id));
    }

    @GetMapping("/{id}/laps")
    public ResponseEntity<List<LapDTO>> laps(@PathVariable final UUID id) {
        return ResponseEntity.ok(sessionQueryService.laps(id));
    }

    /** Métricas agregadas de tuning (suspensão, balanço em curva, frenagem, tração, câmbio, pneus). */
    @GetMapping("/{id}/summary")
    @ApiResponse(responseCode = "200", description = "Resumo de tuning",
            content = @Content(schema = @Schema(implementation = TuningSummaryDTO.class)))
    @ApiResponse(responseCode = "404", description = "Sessão não encontrada", content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<Map<String, Object>> summary(@PathVariable final UUID id) {
        return ResponseEntity.ok(sessionQueryService.summary(id));
    }

    /** Janela da série temporal em ms desde o início da sessão; {@code limit} máximo 10000. */
    @GetMapping("/{id}/samples")
    public ResponseEntity<List<SampleDTO>> samples(@PathVariable final UUID id,
                                                   @RequestParam(defaultValue = "0") final int fromMs,
                                                   @RequestParam(required = false) final Integer toMs,
                                                   @RequestParam(defaultValue = "2000") final int limit) {
        return ResponseEntity.ok(sessionQueryService.samples(id, fromMs, toMs, limit));
    }
}
