package br.com.forza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Protege o contrato publicado: toda rota GET precisa declarar a resposta 200 com o schema do corpo
 * (declarar só 400/404 num {@code @ApiResponse} faz o 200 inferido sumir) e os DTOs de leitura têm
 * que estar em {@code components.schemas}. Sobe o contexto sem banco (mesma técnica da task
 * {@code generateOpenApiDocs}).
 */
@SpringBootTest(properties = {
        "telemetry.udp.enabled=false",
        "spring.liquibase.enabled=false",
        "spring.datasource.url=jdbc:postgresql://localhost:1/sem-banco",
        "spring.datasource.hikari.initialization-fail-timeout=-1"})
@AutoConfigureMockMvc
class OpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    private JsonNode docs() throws Exception {
        final var body = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    @Test
    void readDtos_arePublishedAsSchemas() throws Exception {
        final var schemas = docs().at("/components/schemas");

        for (final String name : List.of("SessionDTO", "SessionPageDTO", "LapDTO", "SampleDTO", "LiveSnapshotDTO", "LiveInfoDTO", "TuningSummaryDTO", "TuningRecommendationDTO", "TuningCarDTO", "TuningHistoryDTO", "TuningHistoryItemDTO")) {
            assertThat(schemas.has(name)).as("schema %s", name).isTrue();
        }
    }

    @Test
    void sessionAndSnapshotSchemas_exposeTheCarName() throws Exception {
        final var schemas = docs().at("/components/schemas");

        assertThat(schemas.at("/SessionDTO/properties").has("carName")).isTrue();
        assertThat(schemas.at("/LiveSnapshotDTO/properties").has("carName")).isTrue();
    }

    @Test
    void everyGetRoute_declaresA200ResponseWithABodySchema() throws Exception {
        final var paths = docs().get("paths");

        paths.fields().forEachRemaining(path -> {
            if (path.getValue().get("get") == null) {
                return; // só rotas GET (ex.: o POST de checkpoint responde 204)
            }
            final var ok = path.getValue().at("/get/responses/200");
            assertThat(ok.isMissingNode()).as("200 de %s", path.getKey()).isFalse();
            assertThat(ok.at("/content").size()).as("corpo 200 de %s", path.getKey()).isPositive();
        });
    }

    @Test
    void errorResponses_areDocumentedAsProblemJson() throws Exception {
        final var paths = docs().get("paths");

        assertThat(paths.at("/~1api~1v1~1sessions~1{id}/get/responses/404/content").has("application/problem+json")).isTrue();
        assertThat(paths.at("/~1api~1v1~1sessions/get/responses/400/content").has("application/problem+json")).isTrue();
        assertThat(paths.at("/~1api~1v1~1live~1snapshot/get/responses/404/content").has("application/problem+json")).isTrue();
    }
}
