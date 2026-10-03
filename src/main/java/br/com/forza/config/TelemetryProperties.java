package br.com.forza.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parâmetros do pipeline de telemetria (prefixo {@code telemetry}) — valores e comentários
 * em {@code application.properties}.
 */
@ConfigurationProperties(prefix = "telemetry")
public record TelemetryProperties(Udp udp,
                                  int sampleEvery,
                                  Duration sessionIdleTimeout,
                                  Duration sessionStationaryTimeout,
                                  Duration flushInterval,
                                  int flushBatchSize,
                                  int minSessionSamples,
                                  Duration liveStaleAfter) {

    public record Udp(boolean enabled, int port, int queueCapacity, int receiveBufferBytes) {
    }
}
