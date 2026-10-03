-- liquibase formatted sql

-- changeset forza-telemetry-service:sessions-v1-initial context:structure labels:sessions
-- comment: Uma sessao = trecho continuo de pista com o mesmo carro (segmentada pelo listener UDP). summary guarda o resumo de tuning calculado no fim da sessao.
-- preconditions onFail:MARK_RAN onError:HALT
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'forza' AND table_name = 'sessions'
CREATE TABLE forza.sessions
(
    id                UUID           NOT NULL PRIMARY KEY,
    game_format       VARCHAR(20)    NOT NULL,
    car_ordinal       INTEGER        NOT NULL,
    car_class         INTEGER        NOT NULL,
    performance_index INTEGER        NOT NULL,
    drivetrain        INTEGER        NOT NULL,
    num_cylinders     INTEGER        NOT NULL,
    engine_max_rpm    REAL           NOT NULL,
    engine_idle_rpm   REAL           NOT NULL,
    track_ordinal     INTEGER,
    started_at        TIMESTAMPTZ    NOT NULL,
    ended_at          TIMESTAMPTZ,
    sample_count      INTEGER        NOT NULL DEFAULT 0,
    summary           JSONB
);
CREATE INDEX idx_sessions_started_at_id ON forza.sessions (started_at DESC, id DESC);
-- rollback DROP TABLE forza.sessions;

-- changeset forza-telemetry-service:laps-v1-initial context:structure labels:laps
-- comment: Tempo de cada volta concluida (LastLap do pacote no instante em que LapNumber incrementa).
-- preconditions onFail:MARK_RAN onError:HALT
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'forza' AND table_name = 'laps'
CREATE TABLE forza.laps
(
    session_id UUID    NOT NULL REFERENCES forza.sessions (id) ON DELETE CASCADE,
    lap_number INTEGER NOT NULL,
    lap_time_s REAL    NOT NULL,
    PRIMARY KEY (session_id, lap_number)
);
-- rollback DROP TABLE forza.laps;

-- changeset forza-telemetry-service:samples-v1-initial context:structure labels:samples
-- comment: Serie temporal a ~20 Hz (60 Hz do jogo / telemetry.sample-every). Rodas sempre na ordem FL, FR, RL, RR. tire_wear so existe no Forza Motorsport. accel_x = lateral, accel_z = longitudinal (espaco local do carro). speed em m/s, tire_temp em graus F.
-- preconditions onFail:MARK_RAN onError:HALT
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'forza' AND table_name = 'samples'
CREATE TABLE forza.samples
(
    session_id    UUID     NOT NULL REFERENCES forza.sessions (id) ON DELETE CASCADE,
    t_ms          INTEGER  NOT NULL,
    lap_number    INTEGER  NOT NULL,
    rpm           REAL     NOT NULL,
    speed         REAL     NOT NULL,
    power         REAL     NOT NULL,
    torque        REAL     NOT NULL,
    boost         REAL     NOT NULL,
    gear          SMALLINT NOT NULL,
    accel         SMALLINT NOT NULL,
    brake         SMALLINT NOT NULL,
    steer         SMALLINT NOT NULL,
    accel_x       REAL     NOT NULL,
    accel_z       REAL     NOT NULL,
    pos_x         REAL     NOT NULL,
    pos_z         REAL     NOT NULL,
    on_rumble     BOOLEAN  NOT NULL,
    susp          REAL[]   NOT NULL,
    slip_ratio    REAL[]   NOT NULL,
    slip_angle    REAL[]   NOT NULL,
    combined_slip REAL[]   NOT NULL,
    tire_temp     REAL[]   NOT NULL,
    tire_wear     REAL[],
    PRIMARY KEY (session_id, t_ms)
);
-- rollback DROP TABLE forza.samples;
