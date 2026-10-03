package br.com.forza.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import br.com.forza.config.SecurityConfig;
import br.com.forza.config.TelemetryProperties;
import br.com.forza.support.PacketBuilder;
import br.com.forza.telemetry.ingest.LiveSnapshot;
import br.com.forza.telemetry.packet.PacketFormat;
import br.com.forza.telemetry.packet.PacketParser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LiveController.class)
@Import({SecurityConfig.class, br.com.forza.telemetry.CarCatalog.class})
@EnableConfigurationProperties(TelemetryProperties.class)
class LiveControllerTest {

    private static final String URL = "/api/v1/live/snapshot";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LiveSnapshot liveSnapshot;

    @MockitoBean
    private OpaqueTokenIntrospector introspector;

    @BeforeEach
    void authenticate() {
        when(introspector.introspect("tok")).thenReturn(new OAuth2IntrospectionAuthenticatedPrincipal("qa.user@workbox.local",
                Map.of("sub", "qa.user@workbox.local"), List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private void stubLatest(final byte[] raw) {
        final var packet = PacketParser.parse(raw).orElseThrow();
        when(liveSnapshot.current(any())).thenReturn(Optional.of(new LiveSnapshot.Reading(packet, Instant.now())));
    }

    @Test
    void snapshot_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void snapshot_noRecentPacket_returnsProblemJson404() throws Exception {
        when(liveSnapshot.current(any())).thenReturn(Optional.empty());

        mockMvc.perform(get(URL).header("Authorization", "Bearer tok"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void snapshot_afterDashPacket_returnsSpeedInKmhAndInputs() throws Exception {
        stubLatest(PacketBuilder.racing(PacketFormat.HORIZON).speed(20f).inputs(200, 0, 0, 0, 4, -10).lapNumber(2).build());

        mockMvc.perform(get(URL).header("Authorization", "Bearer tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameFormat").value("FH4/FH5/FH6"))
                .andExpect(jsonPath("$.speedKmh").value(72.0))
                .andExpect(jsonPath("$.gear").value(4))
                .andExpect(jsonPath("$.accel").value(200))
                .andExpect(jsonPath("$.steer").value(-10))
                .andExpect(jsonPath("$.lapNumber").value(2))
                .andExpect(jsonPath("$.raceOn").value(true));
    }

    @Test
    void snapshot_includesTheExactCarNameWhenTheCatalogKnowsTheOrdinal() throws Exception {
        stubLatest(PacketBuilder.racing(PacketFormat.HORIZON).car(3667, 5, 800, 2, 8).build());

        mockMvc.perform(get(URL).header("Authorization", "Bearer tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carOrdinal").value(3667))
                .andExpect(jsonPath("$.carName").value("2021 Porsche 911 GT3"));
    }

    @Test
    void snapshot_unknownCar_hasNoCarName() throws Exception {
        stubLatest(PacketBuilder.racing(PacketFormat.HORIZON).car(1, 5, 800, 2, 8).build());

        mockMvc.perform(get(URL).header("Authorization", "Bearer tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carName").doesNotExist());
    }

    @Test
    void snapshot_afterSledPacket_hasNullDashFields() throws Exception {
        stubLatest(PacketBuilder.racing(PacketFormat.SLED).build());

        mockMvc.perform(get(URL).header("Authorization", "Bearer tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameFormat").value("Sled"))
                .andExpect(jsonPath("$.speedKmh").doesNotExist())
                .andExpect(jsonPath("$.gear").doesNotExist());
    }
}
