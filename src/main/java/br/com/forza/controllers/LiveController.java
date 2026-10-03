package br.com.forza.controllers;

import br.com.forza.config.TelemetryProperties;
import br.com.forza.exceptions.ResourceNotFoundException;
import br.com.forza.models.dto.LiveSnapshotDTO;
import br.com.forza.telemetry.ingest.LiveSnapshot;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/live")
public class LiveController {

    private final LiveSnapshot liveSnapshot;
    private final TelemetryProperties properties;

    public LiveController(final LiveSnapshot liveSnapshot, final TelemetryProperties properties) {
        this.liveSnapshot = liveSnapshot;
        this.properties = properties;
    }

    /** Último pacote recebido; 404 se nada chegou nos últimos {@code telemetry.live-stale-after}. */
    @GetMapping("/snapshot")
    @ApiResponse(responseCode = "404", description = "Nenhum pacote recente", content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<LiveSnapshotDTO> snapshot() {
        final LiveSnapshot.Reading reading = liveSnapshot.current(properties.liveStaleAfter())
                .orElseThrow(() -> new ResourceNotFoundException("Nenhum pacote de telemetria recebido recentemente"));
        return ResponseEntity.ok(LiveSnapshotDTO.from(reading));
    }
}
