package br.com.forza.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.forza.telemetry.packet.PacketFormat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

class CarCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CarCatalog catalog = new CarCatalog(mapper);

    @Test
    void horizon_resolvesTheExactCarName() {
        assertThat(catalog.nameOf(PacketFormat.HORIZON, 3667)).contains("2021 Porsche 911 GT3");
        assertThat(catalog.nameOf(PacketFormat.HORIZON, 247)).contains("1969 Toyota 2000 GT");
    }

    @Test
    void motorsport2023_usesItsOwnCatalog() {
        assertThat(catalog.nameOf(PacketFormat.FM_DASH, 247)).contains("1969 Toyota 2000GT");
    }

    @Test
    void sameOrdinalCanHaveDifferentNamesPerGameFamily() {
        assertThat(catalog.nameOf(PacketFormat.HORIZON, 249)).contains("1964 Ferrari 250 GTO");
        assertThat(catalog.nameOf(PacketFormat.FM_DASH, 249)).contains("1962 Ferrari 250 GTO");
    }

    @Test
    void unknownOrdinal_isEmptyNeverGuessed() {
        assertThat(catalog.nameOf(PacketFormat.HORIZON, 99_999)).isEmpty();
        assertThat(catalog.nameOf(PacketFormat.HORIZON, -1)).isEmpty();
        assertThat(catalog.nameOf(PacketFormat.HORIZON, 0)).isEmpty();
    }

    @Test
    void formatsWithoutACatalog_areEmptyInsteadOfBorrowingAnotherGamesNames() {
        assertThat(catalog.nameOf(PacketFormat.FM7_DASH, 247)).isEmpty();
        assertThat(catalog.nameOf(PacketFormat.SLED, 247)).isEmpty();
    }

    @Test
    void byGameFormatLabel_matchesByFormat() {
        assertThat(catalog.nameOf("FH4/FH5/FH6", 3667)).contains("2021 Porsche 911 GT3");
        assertThat(catalog.nameOf("FM2023-Dash", 247)).contains("1969 Toyota 2000GT");
        assertThat(catalog.nameOf("FM7-Dash", 247)).isEmpty();
        assertThat(catalog.nameOf("formato-desconhecido", 247)).isEmpty();
        assertThat(catalog.nameOf((String) null, 247)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"car-catalog/horizon.json", "car-catalog/motorsport.json"})
    void catalogData_hasOnlyCleanNamesWithNumericOrdinals(final String resource) throws IOException {
        final Map<String, String> data = mapper.readValue(new ClassPathResource(resource).getInputStream(), new TypeReference<>() {
        });

        assertThat(data).hasSizeGreaterThan(500);
        data.forEach((ordinal, name) -> {
            assertThat(ordinal).matches("\\d+");
            assertThat(name).as("nome do ordinal %s", ordinal).matches("^\\d{4} \\S.*\\S$").isEqualTo(name.trim());
        });
    }
}
