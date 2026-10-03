package br.com.forza.support;

import br.com.forza.models.entities.SampleRow;
import br.com.forza.models.entities.SessionMeta;
import java.time.Instant;
import java.util.UUID;

/** Fábricas de entidades de domínio com defaults neutros (carro em movimento, sem inputs). */
public final class Fixtures {

    private Fixtures() {
    }

    public static SessionMeta session(final int drivetrain) {
        return session(UUID.randomUUID(), drivetrain, null);
    }

    public static SessionMeta session(final UUID id, final int drivetrain, final String summaryJson) {
        return new SessionMeta(id, "FH4/FH5/FH6", 1234, 5, 800, drivetrain, 8, 8000f, 1000f, null,
                Instant.parse("2026-10-03T12:00:00Z"), null, 0, summaryJson);
    }

    public static SampleBuilder sample() {
        return new SampleBuilder();
    }

    public static final class SampleBuilder {
        private int tMs;
        private float rpm = 4000f;
        private float speed = 20f;
        private float power;
        private float torque;
        private float boost;
        private int gear = 3;
        private int accel;
        private int brake;
        private int steer;
        private boolean onRumble;
        private float[] susp = {0.5f, 0.5f, 0.5f, 0.5f};
        private float[] slipRatio = zeros();
        private float[] slipAngle = zeros();
        private float[] tireTemp = {150f, 150f, 150f, 150f};
        private float[] tireWear;

        public SampleBuilder tMs(final int value) {
            this.tMs = value;
            return this;
        }

        public SampleBuilder rpm(final float value) {
            this.rpm = value;
            return this;
        }

        public SampleBuilder speed(final float value) {
            this.speed = value;
            return this;
        }

        public SampleBuilder power(final float value) {
            this.power = value;
            return this;
        }

        public SampleBuilder torque(final float value) {
            this.torque = value;
            return this;
        }

        public SampleBuilder boost(final float value) {
            this.boost = value;
            return this;
        }

        public SampleBuilder gear(final int value) {
            this.gear = value;
            return this;
        }

        public SampleBuilder accel(final int value) {
            this.accel = value;
            return this;
        }

        public SampleBuilder brake(final int value) {
            this.brake = value;
            return this;
        }

        public SampleBuilder steer(final int value) {
            this.steer = value;
            return this;
        }

        public SampleBuilder onRumble(final boolean value) {
            this.onRumble = value;
            return this;
        }

        public SampleBuilder susp(final float... fourWheels) {
            this.susp = fourWheels;
            return this;
        }

        public SampleBuilder slipRatio(final float... fourWheels) {
            this.slipRatio = fourWheels;
            return this;
        }

        public SampleBuilder slipAngle(final float... fourWheels) {
            this.slipAngle = fourWheels;
            return this;
        }

        public SampleBuilder tireTemp(final float... fourWheels) {
            this.tireTemp = fourWheels;
            return this;
        }

        public SampleBuilder tireWear(final float... fourWheels) {
            this.tireWear = fourWheels;
            return this;
        }

        public SampleRow build() {
            return new SampleRow(tMs, 1, rpm, speed, power, torque, boost, gear, accel, brake, steer,
                    0f, 0f, 0f, 0f, onRumble, susp, slipRatio, slipAngle, zeros(), tireTemp, tireWear);
        }

        private static float[] zeros() {
            return new float[4];
        }
    }
}
