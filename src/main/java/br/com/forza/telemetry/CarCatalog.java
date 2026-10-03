package br.com.forza.telemetry;

import br.com.forza.telemetry.packet.PacketFormat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Nome do carro a partir do {@code CarOrdinal} do Data Out (o jogo só manda o id). O catálogo é
 * <b>por família de jogo</b> porque o mesmo ordinal pode ter nome/ano diferentes entre elas
 * (ex.: 249 é "1964 Ferrari 250 GTO" no Horizon e "1962 Ferrari 250 GTO" no Motorsport):
 * {@code car-catalog/horizon.json} (FH4/FH5/FH6, mesmo pacote de 324 bytes) e
 * {@code car-catalog/motorsport.json} (Forza Motorsport 2023). Formatos sem catálogo (FM7, Sled) e
 * ordinais desconhecidos devolvem vazio — nunca se chuta nome. Dados gerados por
 * {@code tools/build-car-catalog.py} (origem e licenças em {@code car-catalog/README.md}).
 */
@Component
public class CarCatalog {

    private final Map<Integer, String> horizon;
    private final Map<Integer, String> motorsport;

    public CarCatalog(final ObjectMapper objectMapper) {
        this.horizon = load(objectMapper, "car-catalog/horizon.json");
        this.motorsport = load(objectMapper, "car-catalog/motorsport.json");
    }

    public Optional<String> nameOf(final PacketFormat format, final int ordinal) {
        final Map<Integer, String> catalog = switch (format) {
            case HORIZON -> horizon;
            case FM_DASH -> motorsport;
            case FM7_DASH, SLED -> Map.of();
        };
        return Optional.ofNullable(catalog.get(ordinal));
    }

    /** Pelo rótulo gravado na sessão ({@code sessions.game_format}). */
    public Optional<String> nameOf(final String gameFormatLabel, final int ordinal) {
        return PacketFormat.fromLabel(gameFormatLabel).flatMap(format -> nameOf(format, ordinal));
    }

    private static Map<Integer, String> load(final ObjectMapper objectMapper, final String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            final Map<String, String> raw = objectMapper.readValue(in, new TypeReference<>() {
            });
            final Map<Integer, String> catalog = new HashMap<>();
            raw.forEach((ordinal, name) -> catalog.put(Integer.valueOf(ordinal), name));
            return Map.copyOf(catalog);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível carregar o catálogo de carros " + resource, e);
        }
    }
}
