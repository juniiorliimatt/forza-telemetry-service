package br.com.forza.tuning;

import br.com.forza.models.dto.TuningSummaryDTO;
import br.com.forza.models.dto.TuningSummaryDTO.CornerPhase;
import br.com.forza.telemetry.Gears;
import br.com.forza.tuning.TuningAggregate.Phase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Combina os resumos de várias sessões do mesmo carro numa visão única, ponderando cada métrica pela
 * quantidade de amostras que a sustenta (uma sessão longa pesa mais que uma curta; uma fase de curva com
 * poucas amostras pesa pouco).
 */
@Component
public class TuningAggregator {

    private static final String[] WHEELS = {"FL", "FR", "RL", "RR"};

    /** Sessão encerrada com resumo gravado ({@code samples} = amostras gravadas na sessão). */
    public record SessionSummary(int samples, TuningSummaryDTO summary) {
    }

    private static final class Weighted {
        private double sum;
        private double weight;

        void add(final double value, final double w) {
            if (w > 0) {
                sum += value * w;
                weight += w;
            }
        }

        double value() {
            return weight == 0 ? 0.0 : sum / weight;
        }

        double weight() {
            return weight;
        }
    }

    public TuningAggregate aggregate(final List<SessionSummary> sessions) {
        final List<SessionSummary> usable = sessions.stream()
                .filter(s -> s.summary() != null && s.summary().suspension() != null && s.samples() > 0)
                .toList();

        final Map<String, Weighted> bottoming = newWheelMap();
        final Map<String, Weighted> topping = newWheelMap();
        final Map<String, Weighted> tires = newWheelMap();
        final Weighted[] entry = phaseAccumulators();
        final Weighted[] mid = phaseAccumulators();
        final Weighted[] exit = phaseAccumulators();
        final Weighted frontLock = new Weighted();
        final Weighted rearLock = new Weighted();
        final Weighted spin = new Weighted();
        final Weighted rumble = new Weighted();
        final Map<String, Weighted> spinByGear = new HashMap<>();
        final Map<String, Weighted> limiterByGear = new HashMap<>();
        long samples = 0;
        long brakingSamples = 0;
        long tractionSamples = 0;
        double topSpeed = 0.0;

        for (final SessionSummary session : usable) {
            final TuningSummaryDTO s = session.summary();
            final double w = session.samples();
            samples += session.samples();

            for (final String wheel : WHEELS) {
                if (s.suspension().containsKey(wheel)) {
                    bottoming.get(wheel).add(s.suspension().get(wheel).bottomingPct(), w);
                    topping.get(wheel).add(s.suspension().get(wheel).toppingPct(), w);
                }
                if (s.tires() != null && s.tires().containsKey(wheel)) {
                    tires.get(wheel).add(s.tires().get(wheel).tempMeanF(), w);
                }
            }
            if (s.cornerBalance() != null) {
                addPhase(entry, s.cornerBalance().entry());
                addPhase(mid, s.cornerBalance().mid());
                addPhase(exit, s.cornerBalance().exit());
            }
            if (s.braking() != null) {
                brakingSamples += s.braking().samples();
                frontLock.add(s.braking().frontLockPct(), s.braking().samples());
                rearLock.add(s.braking().rearLockPct(), s.braking().samples());
            }
            if (s.traction() != null) {
                tractionSamples += s.traction().samples();
                spin.add(s.traction().drivenWheelSpinPct(), s.traction().samples());
                if (s.traction().spinPctByGear() != null) {
                    s.traction().spinPctByGear().forEach((gear, pct) -> {
                        if (Gears.isForward(gear)) {
                            spinByGear.computeIfAbsent(gear, g -> new Weighted()).add(pct, s.traction().samples());
                        }
                    });
                }
            }
            if (s.engine() != null && s.engine().gears() != null) {
                s.engine().gears().forEach((gear, stats) -> {
                    if (Gears.isForward(gear)) {
                        limiterByGear.computeIfAbsent(gear, g -> new Weighted()).add(stats.limiterWithThrottlePct(), w * stats.timePct() / 100.0);
                    }
                });
            }
            if (s.speedKmh() != null) {
                topSpeed = Math.max(topSpeed, s.speedKmh().max());
            }
            if (s.onRumbleStripPct() != null) {
                rumble.add(s.onRumbleStripPct(), w);
            }
        }

        return new TuningAggregate(usable.size(), samples,
                values(bottoming), values(topping), values(tires),
                phase(entry), phase(mid), phase(exit),
                brakingSamples, frontLock.value(), rearLock.value(),
                tractionSamples, spin.value(), values(spinByGear), values(limiterByGear),
                topSpeed, rumble.value());
    }

    private static Map<String, Weighted> newWheelMap() {
        final Map<String, Weighted> map = new HashMap<>();
        for (final String wheel : WHEELS) {
            map.put(wheel, new Weighted());
        }
        return map;
    }

    /** [0] subesterço, [1] sobresterço. O peso de cada um é o nº de amostras da fase; o 2º guarda o total no peso. */
    private static Weighted[] phaseAccumulators() {
        return new Weighted[]{new Weighted(), new Weighted()};
    }

    private static void addPhase(final Weighted[] acc, final CornerPhase phase) {
        if (phase == null || phase.samples() <= 0) {
            return;
        }
        acc[0].add(phase.understeerPct() == null ? 0.0 : phase.understeerPct(), phase.samples());
        acc[1].add(phase.oversteerPct() == null ? 0.0 : phase.oversteerPct(), phase.samples());
    }

    private static Phase phase(final Weighted[] acc) {
        return new Phase((long) acc[0].weight(), acc[0].value(), acc[1].value());
    }

    private static Map<String, Double> values(final Map<String, Weighted> map) {
        final Map<String, Double> out = new HashMap<>();
        map.forEach((key, w) -> {
            if (w.weight() > 0) {
                out.put(key, w.value());
            }
        });
        return out;
    }
}
