package br.com.forza.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.forza.config.SecurityConfig;
import br.com.forza.exceptions.InvalidSetupException;
import br.com.forza.exceptions.ResourceNotFoundException;
import br.com.forza.exceptions.SetupConflictException;
import br.com.forza.models.dto.SetupDTO;
import br.com.forza.models.dto.SetupRequest;
import br.com.forza.telemetry.PerformanceClass;
import br.com.forza.tuning.SetupService;
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

@WebMvcTest(SetupController.class)
@Import(SecurityConfig.class)
class SetupControllerTest {

    private static final String URL = "/api/v1/tuning/cars/3667/S2/setup";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SetupService setupService;

    @MockitoBean
    private OpaqueTokenIntrospector introspector;

    @BeforeEach
    void authenticate() {
        when(introspector.introspect("tok")).thenReturn(new OAuth2IntrospectionAuthenticatedPrincipal("qa", Map.of("sub", "qa"),
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("MODULE_FORZA"))));
    }

    private static SetupDTO dto(final boolean confirmed) {
        return new SetupDTO(confirmed, 6, Map.of("tire_pressure_front", 1.8), Map.of(),
                List.of(new SetupDTO.FieldDTO("tire_pressure_front", "pneus", "Pressão dos pneus dianteiros", "FRONT", "bar", 1.0, 3.8, false)),
                confirmed ? Instant.parse("2026-10-13T12:00:00Z") : null);
    }

    @Test
    void get_withoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void get_returnsTheSetupWithTheFieldLimits() throws Exception {
        when(setupService.get(3667, PerformanceClass.S2)).thenReturn(dto(false));

        mockMvc.perform(get(URL).header("Authorization", "Bearer tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(false))
                .andExpect(jsonPath("$.gearCount").value(6))
                .andExpect(jsonPath("$.values.tire_pressure_front").value(1.8))
                .andExpect(jsonPath("$.fields[0].key").value("tire_pressure_front"))
                .andExpect(jsonPath("$.fields[0].min").value(1.0))
                .andExpect(jsonPath("$.fields[0].max").value(3.8));
    }

    @Test
    void put_savesAndReturnsTheConfirmedSetup() throws Exception {
        when(setupService.save(eq(3667), eq(PerformanceClass.S2), any(SetupRequest.class))).thenReturn(dto(true));

        mockMvc.perform(put(URL).header("Authorization", "Bearer tok").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gearCount\":6,\"values\":{\"tire_pressure_front\":1.8},\"ranges\":{\"spring_front\":{\"min\":20,\"max\":220}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(true));
    }

    @Test
    void put_outOfRange_is422ProblemDetailsWithTheErrors() throws Exception {
        when(setupService.save(eq(3667), eq(PerformanceClass.S2), any(SetupRequest.class)))
                .thenThrow(new InvalidSetupException(List.of("tire_pressure_front deve estar entre 1 e 3,8.")));

        mockMvc.perform(put(URL).header("Authorization", "Bearer tok").contentType(MediaType.APPLICATION_JSON).content("{\"gearCount\":6,\"values\":{}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errors[0]").value("tire_pressure_front deve estar entre 1 e 3,8."));
    }

    @Test
    void apply_returnsTheUpdatedSetup_andConflictWhenThereIsNothingToApply() throws Exception {
        when(setupService.apply(3667, PerformanceClass.S2)).thenReturn(dto(true));
        mockMvc.perform(post(URL + "/apply").header("Authorization", "Bearer tok")).andExpect(status().isOk()).andExpect(jsonPath("$.confirmed").value(true));

        when(setupService.apply(3668, PerformanceClass.S2)).thenThrow(new SetupConflictException("Não há ajuste com valor-alvo para aplicar neste ciclo."));
        mockMvc.perform(post("/api/v1/tuning/cars/3668/S2/setup/apply").header("Authorization", "Bearer tok"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void delete_is204_andNotFoundWhenThereIsNoSetup() throws Exception {
        mockMvc.perform(delete(URL).header("Authorization", "Bearer tok")).andExpect(status().isNoContent());
        verify(setupService).delete(3667, PerformanceClass.S2);

        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Nenhum setup")).when(setupService).delete(1, PerformanceClass.S2);
        mockMvc.perform(delete("/api/v1/tuning/cars/1/S2/setup").header("Authorization", "Bearer tok")).andExpect(status().isNotFound());
    }
}
