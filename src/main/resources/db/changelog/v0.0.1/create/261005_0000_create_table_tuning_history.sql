-- liquibase formatted sql

-- changeset forza-telemetry-service:tuning-history-v1-initial context:structure labels:tuning
-- comment: Histórico de tunings: foto da recomendação (JSONB) gravada ao reiniciar a coleta de um carro, para consultar depois mesmo que as sessões sejam apagadas ou o setup mude.
-- preconditions onFail:MARK_RAN onError:HALT
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'forza' AND table_name = 'tuning_history'
CREATE TABLE forza.tuning_history
(
    id                UUID PRIMARY KEY,
    game_format       VARCHAR(20)  NOT NULL,
    car_ordinal       INTEGER      NOT NULL,
    car_name          VARCHAR(200),
    car_class         INTEGER      NOT NULL,
    performance_index INTEGER      NOT NULL,
    drivetrain        VARCHAR(10)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    window_from       TIMESTAMPTZ  NOT NULL,
    window_to         TIMESTAMPTZ  NOT NULL,
    sessions          INTEGER      NOT NULL,
    samples           BIGINT       NOT NULL,
    adjustments       INTEGER      NOT NULL,
    recommendation    JSONB        NOT NULL
);

CREATE INDEX idx_tuning_history_format_created ON forza.tuning_history (game_format, created_at DESC);
-- rollback DROP TABLE forza.tuning_history;
