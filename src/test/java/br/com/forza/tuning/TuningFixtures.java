package br.com.forza.tuning;

import br.com.forza.tuning.TuningAggregate.Phase;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Agregado "saudável": nada a ajustar. Cada teste muda só o que quer exercitar. */
final class TuningFixtures {

    static final int FWD = 0;
    static final int RWD = 1;
    static final int AWD = 2;

    private TuningFixtures() {
    }

    static TuningAggregate healthy() {
        return new TuningAggregate(12, 20_000,
                wheels(0.0), wheels(0.0), wheels(190.0),
                new Phase(600, 10.0, 10.0), new Phase(600, 10.0, 10.0), new Phase(600, 10.0, 10.0),
                600, 1.0, 1.0,
                800, 5.0, Map.of("1", 8.0, "2", 6.0, "3", 3.0),
                Map.of("1", 0.0, "2", 0.0, "3", 0.0, "4", 0.5, "5", 1.0, "6", 1.5),
                280.0, 2.0);
    }

    static TuningAggregate with(final UnaryOperator<Builder> change) {
        return change.apply(new Builder(healthy())).build();
    }

    static Map<String, Double> wheels(final double value) {
        return Map.of("FL", value, "FR", value, "RL", value, "RR", value);
    }

    static Map<String, Double> wheels(final double fl, final double fr, final double rl, final double rr) {
        return Map.of("FL", fl, "FR", fr, "RL", rl, "RR", rr);
    }

    static final class Builder {
        private TuningAggregate a;

        Builder(final TuningAggregate base) {
            this.a = base;
        }

        Builder bottoming(final Map<String, Double> v) {
            a = new TuningAggregate(a.sessions(), a.samples(), v, a.toppingPct(), a.tireTempF(), a.entry(), a.mid(), a.exit(),
                    a.brakingSamples(), a.frontLockPct(), a.rearLockPct(), a.tractionSamples(), a.spinPct(), a.spinPctByGear(),
                    a.limiterPctByGear(), a.topSpeedKmh(), a.onRumbleStripPct());
            return this;
        }

        Builder tires(final Map<String, Double> v) {
            a = new TuningAggregate(a.sessions(), a.samples(), a.bottomingPct(), a.toppingPct(), v, a.entry(), a.mid(), a.exit(),
                    a.brakingSamples(), a.frontLockPct(), a.rearLockPct(), a.tractionSamples(), a.spinPct(), a.spinPctByGear(),
                    a.limiterPctByGear(), a.topSpeedKmh(), a.onRumbleStripPct());
            return this;
        }

        Builder phases(final Phase entry, final Phase mid, final Phase exit) {
            a = new TuningAggregate(a.sessions(), a.samples(), a.bottomingPct(), a.toppingPct(), a.tireTempF(), entry, mid, exit,
                    a.brakingSamples(), a.frontLockPct(), a.rearLockPct(), a.tractionSamples(), a.spinPct(), a.spinPctByGear(),
                    a.limiterPctByGear(), a.topSpeedKmh(), a.onRumbleStripPct());
            return this;
        }

        Builder braking(final long samples, final double front, final double rear) {
            a = new TuningAggregate(a.sessions(), a.samples(), a.bottomingPct(), a.toppingPct(), a.tireTempF(), a.entry(), a.mid(), a.exit(),
                    samples, front, rear, a.tractionSamples(), a.spinPct(), a.spinPctByGear(),
                    a.limiterPctByGear(), a.topSpeedKmh(), a.onRumbleStripPct());
            return this;
        }

        Builder traction(final long samples, final double spin, final Map<String, Double> byGear) {
            a = new TuningAggregate(a.sessions(), a.samples(), a.bottomingPct(), a.toppingPct(), a.tireTempF(), a.entry(), a.mid(), a.exit(),
                    a.brakingSamples(), a.frontLockPct(), a.rearLockPct(), samples, spin, byGear,
                    a.limiterPctByGear(), a.topSpeedKmh(), a.onRumbleStripPct());
            return this;
        }

        Builder limiter(final Map<String, Double> byGear) {
            a = new TuningAggregate(a.sessions(), a.samples(), a.bottomingPct(), a.toppingPct(), a.tireTempF(), a.entry(), a.mid(), a.exit(),
                    a.brakingSamples(), a.frontLockPct(), a.rearLockPct(), a.tractionSamples(), a.spinPct(), a.spinPctByGear(),
                    byGear, a.topSpeedKmh(), a.onRumbleStripPct());
            return this;
        }

        TuningAggregate build() {
            return a;
        }
    }
}
