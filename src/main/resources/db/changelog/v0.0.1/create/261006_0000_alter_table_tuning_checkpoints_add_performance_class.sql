-- liquibase formatted sql

-- changeset forza-telemetry-service:tuning-checkpoints-v2-performance-class context:structure labels:tuning
-- comment: A coleta de tuning passa a ser por carro E classe de PI (cada classe é uma build). Os marcos já existentes valiam para o carro inteiro: são replicados para as 7 classes, preservando o que o usuário já havia zerado.
-- preconditions onFail:MARK_RAN onError:HALT
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'forza' AND table_name = 'tuning_checkpoints' AND column_name = 'performance_class'
ALTER TABLE forza.tuning_checkpoints ADD COLUMN performance_class VARCHAR(3);

ALTER TABLE forza.tuning_checkpoints DROP CONSTRAINT tuning_checkpoints_pkey;

INSERT INTO forza.tuning_checkpoints (game_format, car_ordinal, since, performance_class)
SELECT c.game_format, c.car_ordinal, c.since, v.class_name
FROM forza.tuning_checkpoints c
         CROSS JOIN (VALUES ('D'), ('C'), ('B'), ('A'), ('S1'), ('S2'), ('R')) AS v(class_name)
WHERE c.performance_class IS NULL;

DELETE FROM forza.tuning_checkpoints WHERE performance_class IS NULL;

ALTER TABLE forza.tuning_checkpoints ALTER COLUMN performance_class SET NOT NULL;

ALTER TABLE forza.tuning_checkpoints ADD PRIMARY KEY (game_format, car_ordinal, performance_class);
-- rollback ALTER TABLE forza.tuning_checkpoints DROP CONSTRAINT tuning_checkpoints_pkey;
-- rollback DELETE FROM forza.tuning_checkpoints WHERE performance_class <> 'A';
-- rollback ALTER TABLE forza.tuning_checkpoints DROP COLUMN performance_class;
-- rollback ALTER TABLE forza.tuning_checkpoints ADD PRIMARY KEY (game_format, car_ordinal);
