package br.com.forza.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Resource server: valida os access tokens emitidos pelo workbox-api via introspecção
 * remota (POST /api/v1/auth/introspect, client credentials) — nunca decodifica o JWT
 * localmente nem conhece jwt.secret/JWT_SECRET. Sem fluxo de login próprio.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${introspection.uri}")
    private String introspectionUri;

    @Value("${introspection.client-id}")
    private String introspectionClientId;

    @Value("${introspection.client-secret}")
    private String introspectionClientSecret;


    @Value("${cors.allowed-origins:http://localhost:7053,http://127.0.0.1:7053}")
    private List<String> allowedOrigins;

    /** Filter chain única do serviço — todo endpoint exige token opaco válido, exceto health e Swagger. */
    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity httpSecurity, final OpaqueTokenIntrospector introspector) throws Exception {
        httpSecurity.csrf(AbstractHttpConfigurer::disable);
        httpSecurity.httpBasic(AbstractHttpConfigurer::disable);
        httpSecurity.cors(cors -> cors.configurationSource(corsConfigurationSource()));

        httpSecurity.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                .anyRequest().authenticated()
        );

        httpSecurity.oauth2ResourceServer(
            oauth2 -> oauth2.opaqueToken(
                opaque -> opaque.introspector(introspector)));
        return httpSecurity.build();
    }

    /** Origens liberadas pra chamadas com credenciais — configurável via {@code cors.allowed-origins}, default aponta pro workbox-app local. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        final var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        final var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** Introspector custom ({@link WorkboxTokenIntrospector}) que chama o endpoint de introspecção do workbox-api via client credentials — não usa nenhuma implementação padrão do Spring porque o token validado é opaco (não JWT decodificável aqui). */
    @Bean
    public OpaqueTokenIntrospector opaqueTokenIntrospector() {
        return new WorkboxTokenIntrospector(RestClient.create(), introspectionUri,
            introspectionClientId, introspectionClientSecret);
    }
}
