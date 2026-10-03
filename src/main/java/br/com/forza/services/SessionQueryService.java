package br.com.forza.services;

import br.com.forza.exceptions.InvalidCursorException;
import br.com.forza.exceptions.ResourceNotFoundException;
import br.com.forza.models.dto.LapDTO;
import br.com.forza.models.dto.SampleDTO;
import br.com.forza.models.dto.SessionDTO;
import br.com.forza.models.dto.SessionPageDTO;
import br.com.forza.models.entities.SessionMeta;
import br.com.forza.repositories.LapRepository;
import br.com.forza.repositories.SampleRepository;
import br.com.forza.repositories.SessionRepository;
import br.com.forza.telemetry.summary.SummaryCalculator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SessionQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SAMPLES = 10_000;

    private final SessionRepository sessionRepository;
    private final LapRepository lapRepository;
    private final SampleRepository sampleRepository;
    private final SummaryCalculator summaryCalculator;
    private final ObjectMapper objectMapper;

    public SessionQueryService(final SessionRepository sessionRepository,
                               final LapRepository lapRepository,
                               final SampleRepository sampleRepository,
                               final SummaryCalculator summaryCalculator,
                               final ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.lapRepository = lapRepository;
        this.sampleRepository = sampleRepository;
        this.summaryCalculator = summaryCalculator;
        this.objectMapper = objectMapper;
    }

    /** Cursor opaco = base64url("{epochMicros}|{uuid}") da última sessão da página anterior. */
    public SessionPageDTO list(final String cursor, final int requestedSize) {
        final int size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
        final Instant cursorStartedAt;
        final UUID cursorId;
        if (cursor == null || cursor.isBlank()) {
            cursorStartedAt = null;
            cursorId = null;
        } else {
            final String[] parts = decodeCursor(cursor);
            cursorStartedAt = Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS);
            cursorId = UUID.fromString(parts[1]);
        }
        final List<SessionMeta> found = sessionRepository.findPage(cursorStartedAt, cursorId, size + 1);
        final boolean hasMore = found.size() > size;
        final List<SessionMeta> page = hasMore ? found.subList(0, size) : found;
        final String next = hasMore ? encodeCursor(page.get(page.size() - 1)) : null;
        return new SessionPageDTO(page.stream().map(SessionDTO::from).toList(), next);
    }

    public SessionDTO get(final UUID id) {
        return SessionDTO.from(require(id));
    }

    public List<LapDTO> laps(final UUID id) {
        require(id);
        return lapRepository.findBySession(id).stream().map(LapDTO::from).toList();
    }

    /** Resumo gravado no fim da sessão; se ainda não existe (sessão ativa ou falha), calcula na hora sem persistir. */
    public Map<String, Object> summary(final UUID id) {
        final SessionMeta meta = require(id);
        try {
            if (meta.summaryJson() != null) {
                return objectMapper.readValue(meta.summaryJson(), new TypeReference<Map<String, Object>>() {
                });
            }
        } catch (JsonProcessingException e) {
            // JSON gravado ilegível: cai no recálculo abaixo.
        }
        return summaryCalculator.calculate(meta, sampleRepository.findAll(id), lapRepository.findBySession(id));
    }

    public List<SampleDTO> samples(final UUID id, final int fromMs, final Integer toMs, final int requestedLimit) {
        require(id);
        final int limit = Math.max(1, Math.min(requestedLimit, MAX_SAMPLES));
        return sampleRepository.findRange(id, Math.max(0, fromMs), toMs, limit).stream().map(SampleDTO::from).toList();
    }

    private SessionMeta require(final UUID id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sessão não encontrada: " + id));
    }

    private static String encodeCursor(final SessionMeta last) {
        final long micros = ChronoUnit.MICROS.between(Instant.EPOCH, last.startedAt());
        final String raw = micros + "|" + last.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decodeCursor(final String cursor) {
        try {
            final String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            final String[] parts = raw.split("\\|", 2);
            if (parts.length != 2) {
                throw new InvalidCursorException();
            }
            Long.parseLong(parts[0]);
            UUID.fromString(parts[1]);
            return parts;
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException();
        }
    }
}
