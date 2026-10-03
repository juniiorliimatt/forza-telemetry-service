package br.com.forza.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WorkboxTokenIntrospectorTest {

    private static final String URI = "http://workbox-api/api/v1/auth/introspect";

    private MockRestServiceServer server;
    private WorkboxTokenIntrospector introspector;

    @BeforeEach
    void setUp() {
        final var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        introspector = new WorkboxTokenIntrospector(builder.build(), URI, "forza-telemetry-service", "segredo");
    }

    @Test
    void introspect_activeToken_returnsPrincipalWithSubjectRolesAndExpiry() {
        server.expect(requestTo(URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + Base64.getEncoder().encodeToString("forza-telemetry-service:segredo".getBytes())))
                .andExpect(header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE))
                .andExpect(content().string("token=abc123"))
                .andRespond(withSuccess("{\"active\":true,\"sub\":\"qa.user@workbox.local\",\"roles\":[\"ROLE_USER\",\"ROLE_ADMIN\"],\"exp\":1760000000}",
                        MediaType.APPLICATION_JSON));

        final var principal = introspector.introspect("abc123");

        assertThat(principal.getName()).isEqualTo("qa.user@workbox.local");
        assertThat(principal.getAuthorities()).extracting("authority").containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        assertThat(principal.<Instant>getAttribute("exp")).isEqualTo(Instant.ofEpochSecond(1_760_000_000L));
        server.verify();
    }

    @Test
    void introspect_activeTokenWithoutRoles_hasNoAuthorities() {
        server.expect(requestTo(URI)).andRespond(withSuccess("{\"active\":true,\"sub\":\"x\"}", MediaType.APPLICATION_JSON));

        assertThat(introspector.introspect("t").getAuthorities()).isEmpty();
    }

    @Test
    void introspect_inactiveToken_throwsBadOpaqueToken() {
        server.expect(requestTo(URI)).andRespond(withSuccess("{\"active\":false}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> introspector.introspect("t")).isInstanceOf(BadOpaqueTokenException.class);
    }

    @Test
    void introspect_missingActiveClaim_throwsBadOpaqueToken() {
        server.expect(requestTo(URI)).andRespond(withSuccess("{\"sub\":\"x\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> introspector.introspect("t")).isInstanceOf(BadOpaqueTokenException.class);
    }

    @Test
    void introspect_emptyBody_throwsBadOpaqueToken() {
        server.expect(requestTo(URI)).andRespond(withSuccess());

        assertThatThrownBy(() -> introspector.introspect("t")).isInstanceOf(BadOpaqueTokenException.class);
    }

    @Test
    void introspect_workboxApiFailure_throwsBadOpaqueTokenInsteadOfLeakingTheError() {
        server.expect(requestTo(URI)).andRespond(withServerError());

        assertThatThrownBy(() -> introspector.introspect("t")).isInstanceOf(BadOpaqueTokenException.class)
                .hasMessageContaining("introspecção");
    }
}
