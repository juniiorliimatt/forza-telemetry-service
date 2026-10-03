package br.com.forza.controllers;

import br.com.forza.config.TelemetryProperties;
import br.com.forza.exceptions.ResourceNotFoundException;
import br.com.forza.models.dto.LiveInfoDTO;
import br.com.forza.models.dto.LiveSnapshotDTO;
import br.com.forza.telemetry.CarCatalog;
import br.com.forza.telemetry.ingest.LiveSnapshot;
import br.com.forza.telemetry.packet.TelemetryPacket;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/live")
public class LiveController {

    private final LiveSnapshot liveSnapshot;
    private final TelemetryProperties properties;
    private final CarCatalog carCatalog;
    private final List<String> advertisedHosts;
    private final int advertisedPort;

    public LiveController(final LiveSnapshot liveSnapshot,
                          final TelemetryProperties properties,
                          final CarCatalog carCatalog,
                          @Value("${telemetry.advertised-hosts:}") final String advertisedHosts,
                          @Value("${telemetry.advertised-port:${telemetry.udp.port:5310}}") final int advertisedPort) {
        this.liveSnapshot = liveSnapshot;
        this.properties = properties;
        this.carCatalog = carCatalog;
        this.advertisedHosts = Arrays.stream(advertisedHosts.split(",")).map(String::trim).filter(host -> !host.isEmpty()).toList();
        this.advertisedPort = advertisedPort;
    }

    /** IP(s) e porta UDP pra configurar no Data Out do jogo (IP vazio = não configurado no serviço). */
    @GetMapping("/info")
    public ResponseEntity<LiveInfoDTO> info() {
        return ResponseEntity.ok(new LiveInfoDTO(advertisedHosts, advertisedPort));
    }

    /** Último pacote recebido; 404 se nada chegou nos últimos {@code telemetry.live-stale-after}. */
    @GetMapping("/snapshot")
    @ApiResponse(responseCode = "200", description = "Último pacote recebido",
            content = @Content(schema = @Schema(implementation = LiveSnapshotDTO.class)))
    @ApiResponse(responseCode = "404", description = "Nenhum pacote recente", content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<LiveSnapshotDTO> snapshot() {
        final LiveSnapshot.Reading reading = liveSnapshot.current(properties.liveStaleAfter())
                .orElseThrow(() -> new ResourceNotFoundException("Nenhum pacote de telemetria recebido recentemente"));
        final TelemetryPacket packet = reading.packet();
        final String carName = carCatalog.nameOf(packet.format(), packet.carOrdinal()).orElse(null);
        return ResponseEntity.ok(LiveSnapshotDTO.from(reading, carName));
    }
}
