package br.com.forza.tuning;

import br.com.forza.repositories.CarSetupRepository;
import br.com.forza.telemetry.PerformanceClass;
import br.com.forza.telemetry.packet.PacketFormat;
import br.com.forza.tuning.SetupCatalog.Range;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Lê e grava o setup do carro (JSON ↔ mapas tipados). Só o essencial: as regras ficam em {@link SetupService} e {@link TuningService}. */
@Component
public class CarSetupStore {

    private static final String GAME_FORMAT = PacketFormat.HORIZON.label();
    private static final TypeReference<Map<String, Double>> VALUES = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Range>> RANGES = new TypeReference<>() {
    };

    /** Setup conferido e aceito pelo usuário. */
    public record CarSetup(int gearCount, Map<String, Double> values, Map<String, Range> ranges, Instant confirmedAt) {
    }

    private final CarSetupRepository repository;
    private final ObjectMapper objectMapper;

    public CarSetupStore(final CarSetupRepository repository, final ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Optional<CarSetup> find(final int carOrdinal, final PerformanceClass performanceClass) {
        return repository.find(GAME_FORMAT, carOrdinal, performanceClass).map(entry -> {
            try {
                return new CarSetup(entry.gearCount(), objectMapper.readValue(entry.valuesJson(), VALUES),
                        objectMapper.readValue(entry.rangesJson(), RANGES), entry.confirmedAt());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Setup ilegível para o carro " + carOrdinal + " (" + performanceClass + ")", e);
            }
        });
    }

    public void save(final int carOrdinal, final PerformanceClass performanceClass, final CarSetup setup) {
        try {
            repository.upsert(new CarSetupRepository.Entry(GAME_FORMAT, carOrdinal, performanceClass, setup.gearCount(),
                    objectMapper.writeValueAsString(setup.values()), objectMapper.writeValueAsString(setup.ranges()), setup.confirmedAt()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Não foi possível serializar o setup do carro " + carOrdinal, e);
        }
    }

    public boolean delete(final int carOrdinal, final PerformanceClass performanceClass) {
        return repository.delete(GAME_FORMAT, carOrdinal, performanceClass);
    }
}
