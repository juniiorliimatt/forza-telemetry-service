package br.com.forza.controllers;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.forza.config.SecurityConfig;
import br.com.forza.config.TelemetryProperties;
import br.com.forza.telemetry.ingest.LiveSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** {@code GET /api/v1/live/info}: IP(s) e porta UDP que o jogo deve usar, anunciados por configuração. */
@WebMvcTest(LiveController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(TelemetryProperties.class)
@TestPropertySource(properties = {"telemetry.advertised-hosts= 192.168.0.10 , ,10.0.0.5", "telemetry.advertised-port=5311"})
class LiveInfoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LiveSnapshot liveSnapshot;

    @MockitoBean
    private OpaqueTokenIntrospector introspector;

    @BeforeEach
    void authenticate() {
        when(introspector.introspect("tok")).thenReturn(new OAuth2IntrospectionAuthenticatedPrincipal("qa",
                Map.of("sub", "qa"), List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void info_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/live/info")).andExpect(status().isUnauthorized());
    }

    @Test
    void info_returnsConfiguredHostsTrimmedWithoutBlanksAndTheAdvertisedPort() throws Exception {
        mockMvc.perform(get("/api/v1/live/info").header("Authorization", "Bearer tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostAddresses.length()").value(2))
                .andExpect(jsonPath("$.hostAddresses[0]").value("192.168.0.10"))
                .andExpect(jsonPath("$.hostAddresses[1]").value("10.0.0.5"))
                .andExpect(jsonPath("$.udpPort").value(5311));
    }
}
