-- liquibase formatted sql

-- changeset forza-telemetry-service:car-setups-v1-initial context:structure labels:tuning
-- comment: Setup atual do carro (por família de jogo, carro e classe de PI), informado pelo usuário: o Data Out não traz os valores. setup_values e ranges são JSONB (catálogo de campos no código); ranges guarda o mín/máx do slider de molas e aerodinâmica, que variam por carro. confirmed_at = quando o usuário conferiu e aceitou estes valores (início da coleta).
-- preconditions onFail:MARK_RAN onError:HALT
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'forza' AND table_name = 'car_setups'
CREATE TABLE forza.car_setups
(
    game_format       VARCHAR(20) NOT NULL,
    car_ordinal       INTEGER     NOT NULL,
    performance_class VARCHAR(3)  NOT NULL,
    gear_count        INTEGER     NOT NULL,
    setup_values      JSONB       NOT NULL,
    ranges            JSONB       NOT NULL,
    confirmed_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (game_format, car_ordinal, performance_class)
);
-- rollback DROP TABLE forza.car_setups;
