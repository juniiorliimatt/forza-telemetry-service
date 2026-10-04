-- liquibase formatted sql

-- changeset forza-telemetry-service:sessions-v2-add-samples-purged-at context:structure labels:sessions
-- comment: Marca a sessao cujas amostras brutas (forza.samples) foram apagadas ao reiniciar a coleta do carro/classe. Resumo (summary), voltas e sample_count continuam; so a aba de telemetria fica sem dados. NULL = amostras ainda guardadas.
-- preconditions onFail:MARK_RAN onError:HALT
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'forza' AND table_name = 'sessions' AND column_name = 'samples_purged_at'
ALTER TABLE forza.sessions ADD COLUMN samples_purged_at TIMESTAMPTZ;
-- rollback ALTER TABLE forza.sessions DROP COLUMN samples_purged_at;
