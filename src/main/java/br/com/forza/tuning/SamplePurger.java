package br.com.forza.tuning;

import br.com.forza.config.TuningProperties;
import br.com.forza.repositories.SampleRepository;
import br.com.forza.repositories.SessionRepository;
import br.com.forza.telemetry.PerformanceClass;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Apaga as amostras brutas ({@code forza.samples}, ~5000 linhas por sessão) das sessões de um carro/classe quando a
 * coleta é reiniciada: o tuning já virou {@code tuning_history} e o resumo de cada sessão fica em
 * {@code sessions.summary}, então o que sobra só serve à aba de telemetria. Preserva as
 * {@code tuning.keep-sample-sessions} sessões mais recentes pra sempre haver telemetria de amostra; elas saem no próximo
 * reinício. Sem retenção por tempo — as amostras ficam até alguém reiniciar a coleta. Roda na transação do chamador.
 */
@Component
public class SamplePurger {

    private final SessionRepository sessionRepository;
    private final SampleRepository sampleRepository;
    private final TuningProperties properties;
    private final Clock clock;

    public SamplePurger(final SessionRepository sessionRepository, final SampleRepository sampleRepository,
                        final TuningProperties properties, final Clock clock) {
        this.sessionRepository = sessionRepository;
        this.sampleRepository = sampleRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return quantas sessões tiveram as amostras apagadas */
    public int purge(final String gameFormat, final int carOrdinal, final PerformanceClass performanceClass) {
        final List<UUID> ids = sessionRepository.findSamplePurgeCandidates(gameFormat, carOrdinal, performanceClass, properties.keepSampleSessions());
        if (ids.isEmpty()) {
            return 0;
        }
        sampleRepository.deleteBySessions(ids);
        sessionRepository.markSamplesPurged(ids, clock.instant());
        return ids.size();
    }
}
