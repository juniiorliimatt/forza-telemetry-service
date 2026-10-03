package br.com.forza.controllers;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.forza.config.SecurityConfig;
import br.com.forza.exceptions.ResourceNotFoundException;
import br.com.forza.models.dto.TuningCarDTO;
import br.com.forza.models.dto.TuningRecommendationDTO;
import br.com.forza.models.dto.TuningRecommendationDTO.TuningGuideDTO;
import br.com.forza.models.dto.TuningRecommendationDTO.TuningReadinessDTO;
import br.com.forza.models.dto.TuningRecommendationDTO.TuningSuggestionDTO;
import br.com.forza.tuning.TuningService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TuningController.class)
@Import(SecurityConfig.class)
class TuningControllerTest {

    private static final String BASE = "/api/v1/tuning";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TuningService tuningService;

    @MockitoBean
    private OpaqueTokenIntrospector introspector;

    @BeforeEach
    void authenticate() {
        when(introspector.introspect("tok")).thenReturn(new OAuth2IntrospectionAuthenticatedPrincipal("qa",
                Map.of("sub", "qa"), List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void cars_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE + "/cars")).andExpect(status().isUnauthorized());
    }

    @Test
    void cars_returnsTheCarsWithProgress() throws Exception {
        when(tuningService.cars()).thenReturn(List.of(new TuningCarDTO(3667, "2021 Porsche 911 GT3", 4, 812, "RWD", 7, 9000, 10, false, Instant.parse("2026-10-10T12:00:00Z"))));

        mockMvc.perform(get(BASE + "/cars").header("Authorization", "Bearer tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].carOrdinal").value(3667))
                .andExpect(jsonPath("$[0].carName").value("2021 Porsche 911 GT3"))
                .andExpect(jsonPath("$[0].sessions").value(7))
                .andExpect(jsonPath("$[0].requiredSessions").value(10))
                .andExpect(jsonPath("$[0].ready").value(false));
    }

    @Test
    void recommendation_returnsGuidesAndTheCycle() throws Exception {
        final var suggestion = new TuningSuggestionDTO(1, true, "molas", "Altura do solo traseira", "REAR", "INCREASE", "porque", "7% no fundo");
        final var guide = new TuningGuideDTO("molas", "Molas", "ADJUST", "1 ajuste sugerido", List.of("nota"), List.of(suggestion));
        when(tuningService.recommendation(3667)).thenReturn(new TuningRecommendationDTO(3667, "2021 Porsche 911 GT3", 4, 812, "RWD",
                new TuningReadinessDTO(true, 12, 10, 12000, 6000, List.of()), Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-10T00:00:00Z"), null,
                List.of(guide), List.of(suggestion)));

        mockMvc.perform(get(BASE + "/cars/3667").header("Authorization", "Bearer tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readiness.ready").value(true))
                .andExpect(jsonPath("$.guides[0].id").value("molas"))
                .andExpect(jsonPath("$.guides[0].suggestions[0].direction").value("INCREASE"))
                .andExpect(jsonPath("$.thisCycle[0].parameter").value("Altura do solo traseira"));
    }

    @Test
    void recommendation_unknownCar_returnsProblemJson404() throws Exception {
        when(tuningService.recommendation(99)).thenThrow(new ResourceNotFoundException("Carro sem sessões: 99"));

        mockMvc.perform(get(BASE + "/cars/99").header("Authorization", "Bearer tok"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void recommendation_nonNumericCar_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/cars/abc").header("Authorization", "Bearer tok")).andExpect(status().isBadRequest());
    }

    @Test
    void checkpoint_resetsTheCollection_returns204() throws Exception {
        mockMvc.perform(post(BASE + "/cars/3667/checkpoint").header("Authorization", "Bearer tok")).andExpect(status().isNoContent());

        verify(tuningService).resetCollection(3667);
    }

    @Test
    void checkpoint_withoutToken_returnsUnauthorizedAndDoesNotReset() throws Exception {
        mockMvc.perform(post(BASE + "/cars/3667/checkpoint")).andExpect(status().isUnauthorized());

        org.mockito.Mockito.verify(tuningService, org.mockito.Mockito.never()).resetCollection(anyInt());
    }

    @Test
    void cors_allowsThePostPreflightFromTheFrontOrigin() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(BASE + "/cars/3667/checkpoint")
                        .header("Origin", "http://localhost:7053").header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk());
    }
}
