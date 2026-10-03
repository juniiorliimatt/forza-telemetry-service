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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Sem IP configurado: lista vazia (o front decide o fallback) e porta = a UDP em escuta. */
@WebMvcTest(LiveController.class)
@Import({SecurityConfig.class, br.com.forza.telemetry.CarCatalog.class})
@EnableConfigurationProperties(TelemetryProperties.class)
class LiveInfoDefaultsControllerTest {

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
    void info_withoutConfiguration_hasNoHostsAndUsesTheListeningUdpPort() throws Exception {
        mockMvc.perform(get("/api/v1/live/info").header("Authorization", "Bearer tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostAddresses.length()").value(0))
                .andExpect(jsonPath("$.udpPort").value(5310));
    }
}
