-- liquibase formatted sql

-- changeset forza-telemetry-service:tuning-checkpoints-v1-initial context:structure labels:tuning
-- comment: Marco de coleta por carro/família de jogo: a recomendação de tuning só considera sessões iniciadas depois dele. O Data Out não traz os valores do setup, então o usuário reinicia a coleta ao aplicar um ajuste.
-- preconditions onFail:MARK_RAN onError:HALT
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'forza' AND table_name = 'tuning_checkpoints'
CREATE TABLE forza.tuning_checkpoints
(
    game_format VARCHAR(20) NOT NULL,
    car_ordinal INTEGER     NOT NULL,
    since       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (game_format, car_ordinal)
);
-- rollback DROP TABLE forza.tuning_checkpoints;
