package br.com.forza.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.forza.config.SecurityConfig;
import br.com.forza.exceptions.InvalidCursorException;
import br.com.forza.exceptions.ResourceNotFoundException;
import br.com.forza.models.dto.LapDTO;
import br.com.forza.models.dto.SampleDTO;
import br.com.forza.models.dto.SessionDTO;
import br.com.forza.models.dto.SessionPageDTO;
import br.com.forza.models.entities.SampleRow;
import br.com.forza.services.SessionQueryService;
import br.com.forza.support.Fixtures;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Usa o {@link SecurityConfig} real; só o introspector (chamada HTTP ao workbox-api) é substituído. */
@WebMvcTest(SessionController.class)
@Import(SecurityConfig.class)
class SessionControllerTest {

    private static final String BASE = "/api/v1/sessions";
    private static final String TOKEN = "token-valido";
    private static final String TOKEN_SEM_MODULO = "token-sem-modulo";
    private static final String TOKEN_OUTRO_MODULO = "token-outro-modulo";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionQueryService sessionQueryService;

    @MockitoBean
    private OpaqueTokenIntrospector introspector;

    @BeforeEach
    void stubIntrospector() {
        final OAuth2AuthenticatedPrincipal principal = new OAuth2IntrospectionAuthenticatedPrincipal("qa.user@workbox.local",
                Map.of("sub", "qa.user@workbox.local"),
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("MODULE_FORZA")));
        when(introspector.introspect(TOKEN)).thenReturn(principal);
        when(introspector.introspect(TOKEN_SEM_MODULO)).thenReturn(new OAuth2IntrospectionAuthenticatedPrincipal("novato@workbox.local",
                Map.of("sub", "novato@workbox.local"), List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(introspector.introspect(TOKEN_OUTRO_MODULO)).thenReturn(new OAuth2IntrospectionAuthenticatedPrincipal("ana@workbox.local",
                Map.of("sub", "ana@workbox.local"),
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("MODULE_FINANCAS"))));
        when(introspector.introspect("token-revogado")).thenThrow(new BadOpaqueTokenException("Token inativo"));
    }

    private static MockHttpServletRequestBuilder authed(final MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TOKEN);
    }

    private static SessionDTO sessionDto(final UUID id) {
        return SessionDTO.from(Fixtures.session(id, 2, null));
    }

    @Test
    void list_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void list_authenticatedWithoutForzaModule_returnsForbidden() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + TOKEN_SEM_MODULO)).andExpect(status().isForbidden());
    }

    @Test
    void list_withAnotherModuleOnly_returnsForbidden() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + TOKEN_OUTRO_MODULO)).andExpect(status().isForbidden());
    }

    @Test
    void list_withRevokedToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", "Bearer token-revogado")).andExpect(status().isUnauthorized());
    }

    @Test
    void list_withValidToken_returnsPageWithDefaultSize() throws Exception {
        final var id = UUID.randomUUID();
        when(sessionQueryService.list(isNull(), anyInt())).thenReturn(new SessionPageDTO(List.of(sessionDto(id)), "proximo"));

        mockMvc.perform(authed(get(BASE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].drivetrain").value("AWD"))
                .andExpect(jsonPath("$.items[0].active").value(true))
                .andExpect(jsonPath("$.nextCursor").value("proximo"));
        verify(sessionQueryService).list(null, 20);
    }

    @Test
    void list_forwardsCursorAndSize() throws Exception {
        when(sessionQueryService.list(any(), anyInt())).thenReturn(new SessionPageDTO(List.of(), null));

        mockMvc.perform(authed(get(BASE).param("cursor", "abc").param("size", "50"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        verify(sessionQueryService).list("abc", 50);
    }

    @Test
    void list_invalidCursor_returnsProblemJson400() throws Exception {
        when(sessionQueryService.list(any(), anyInt())).thenThrow(new InvalidCursorException());

        mockMvc.perform(authed(get(BASE).param("cursor", "lixo")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void list_nonNumericSize_returnsBadRequestNamingTheParameter() throws Exception {
        mockMvc.perform(authed(get(BASE).param("size", "muitos")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Valor inválido para o parâmetro 'size'"));
    }

    @Test
    void findById_unknownSession_returnsProblemJson404() throws Exception {
        final var id = UUID.randomUUID();
        when(sessionQueryService.get(id)).thenThrow(new ResourceNotFoundException("Sessão não encontrada: " + id));

        mockMvc.perform(authed(get(BASE + "/" + id)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Sessão não encontrada: " + id));
    }

    @Test
    void findById_malformedUuid_returnsBadRequest() throws Exception {
        mockMvc.perform(authed(get(BASE + "/nao-e-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Valor inválido para o parâmetro 'id'"));
    }

    @Test
    void findById_knownSession_returnsMetadata() throws Exception {
        final var id = UUID.randomUUID();
        when(sessionQueryService.get(id)).thenReturn(sessionDto(id));

        mockMvc.perform(authed(get(BASE + "/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carOrdinal").value(1234))
                .andExpect(jsonPath("$.performanceIndex").value(800));
    }

    @Test
    void laps_returnsLapTimes() throws Exception {
        final var id = UUID.randomUUID();
        when(sessionQueryService.laps(id)).thenReturn(List.of(new LapDTO(1, 62.5f), new LapDTO(2, 61.25f)));

        mockMvc.perform(authed(get(BASE + "/" + id + "/laps")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].lapNumber").value(2))
                .andExpect(jsonPath("$[1].lapTimeS").value(61.25));
    }

    @Test
    void summary_returnsTuningSummary() throws Exception {
        final var id = UUID.randomUUID();
        when(sessionQueryService.summary(id)).thenReturn(Map.of("samples", 321, "durationS", 16.0));

        mockMvc.perform(authed(get(BASE + "/" + id + "/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.samples").value(321));
    }

    @Test
    void samples_usesDefaultsWhenParamsAreOmitted() throws Exception {
        final var id = UUID.randomUUID();
        final SampleRow row = Fixtures.sample().tMs(50).gear(4).build();
        when(sessionQueryService.samples(eq(id), anyInt(), any(), anyInt())).thenReturn(List.of(SampleDTO.from(row)));

        mockMvc.perform(authed(get(BASE + "/" + id + "/samples")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tMs").value(50))
                .andExpect(jsonPath("$[0].gear").value(4));
        verify(sessionQueryService).samples(id, 0, null, 2000);
    }

    @Test
    void samples_forwardsWindowAndLimit() throws Exception {
        final var id = UUID.randomUUID();
        when(sessionQueryService.samples(eq(id), anyInt(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(authed(get(BASE + "/" + id + "/samples").param("fromMs", "1000").param("toMs", "5000").param("limit", "300")))
                .andExpect(status().isOk());

        verify(sessionQueryService).samples(id, 1000, 5000, 300);
    }

    @Test
    void anyEndpoint_unexpectedFailure_returnsGeneric500WithoutLeakingDetails() throws Exception {
        when(sessionQueryService.list(any(), anyInt())).thenThrow(new IllegalStateException("senha do banco: segredo"));

        mockMvc.perform(authed(get(BASE)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Erro interno do servidor"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("segredo"))));
    }

    @Test
    void cors_allowedOriginPreflight_isAccepted() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(BASE)
                        .header("Origin", "http://localhost:7053")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Access-Control-Allow-Origin", "http://localhost:7053"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void cors_unknownOrigin_isRejected() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(BASE)
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cors_writeMethodPreflight_isRejected() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(BASE)
                        .header("Origin", "http://localhost:7053")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
    }
}
