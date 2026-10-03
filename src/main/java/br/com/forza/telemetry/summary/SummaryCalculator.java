package br.com.forza.telemetry.summary;

import br.com.forza.models.entities.LapRecord;
import br.com.forza.models.entities.SampleRow;
import br.com.forza.models.entities.SessionMeta;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

/**
 * Resumo de tuning de uma sessão: agrega a série temporal em métricas que apontam qual
 * parâmetro do setup mexer (suspensão, balanço em curva, frenagem, tração, câmbio, pneus).
 * <p>
 * Os limiares abaixo são heurísticas iniciais, a calibrar com dados reais do jogo:
 * "em movimento" = acima de 8 m/s; "em curva" = |esterço| > 25 (escala -127..127);
 * entrada = freio > 40, saída = acelerador >= 150 sem freio; sub/sobresterço = diferença
 * de ±0.15 no slip angle médio normalizado entre eixo dianteiro e traseiro.
 */
@Component
public class SummaryCalculator {

    private static final String[] WHEELS = {"FL", "FR", "RL", "RR"};
    private static final double MOVING_MS = 8.0;
    private static final double SUSPENSION_BOTTOMING = 0.98;
    private static final double SUSPENSION_TOPPING = 0.02;
    private static final double BALANCE_THRESHOLD = 0.15;
    private static final double WATTS_PER_HP = 745.7;

    public Map<String, Object> calculate(final SessionMeta meta, final List<SampleRow> rows, final List<LapRecord> laps) {
        final Map<String, Object> out = new LinkedHashMap<>();
        final int n = rows.size();
        out.put("samples", n);
        out.put("durationS", n == 0 ? 0.0 : round(rows.get(n - 1).tMs() / 1000.0, 1));
        if (n == 0) {
            return out;
        }
        final List<SampleRow> moving = rows.stream().filter(r -> r.speed() > MOVING_MS).toList();

        out.put("suspension", suspension(rows));
        out.put("speedKmh", map(
                "max", round(rows.stream().mapToDouble(r -> r.speed() * 3.6).max().orElse(0.0), 1),
                "meanMoving", round(mean(moving, r -> r.speed() * 3.6), 1)));
        out.put("engine", engine(meta, rows, moving));
        out.put("tires", tires(rows, moving));
        out.put("cornerBalance", map(
                "entry", balance(moving.stream().filter(r -> cornering(r) && r.brake() > 40).toList()),
                "mid", balance(moving.stream().filter(r -> cornering(r) && r.brake() <= 40 && r.accel() < 150).toList()),
                "exit", balance(moving.stream().filter(r -> cornering(r) && r.accel() >= 150 && r.brake() <= 40).toList()),
                "note", "slip angle normalizado: 0 = grip total, >1 = perda de grip; understeer = frente escorrega mais que a traseira"));
        out.put("braking", braking(moving));
        out.put("traction", traction(meta, moving));
        out.put("laps", lapsSummary(laps));
        out.put("onRumbleStripPct", pct(rows.stream().filter(SampleRow::onRumble).count(), n));
        return out;
    }

    private Map<String, Object> suspension(final List<SampleRow> rows) {
        final Map<String, Object> result = new LinkedHashMap<>();
        for (int w = 0; w < 4; w++) {
            final int wheel = w;
            final double[] values = rows.stream().mapToDouble(r -> r.susp()[wheel]).toArray();
            result.put(WHEELS[w], map(
                    "mean", round(mean(values), 2),
                    "p95", round(percentile(values, 0.95), 2),
                    "bottomingPct", pct(count(values, v -> v >= SUSPENSION_BOTTOMING), values.length),
                    "toppingPct", pct(count(values, v -> v <= SUSPENSION_TOPPING), values.length)));
        }
        return result;
    }

    private Map<String, Object> engine(final SessionMeta meta, final List<SampleRow> rows, final List<SampleRow> moving) {
        final SampleRow peakPower = rows.stream().max(Comparator.comparingDouble(SampleRow::power)).orElseThrow();
        final SampleRow peakTorque = rows.stream().max(Comparator.comparingDouble(SampleRow::torque)).orElseThrow();
        final double limiterRpm = 0.97 * meta.engineMaxRpm();

        final Map<Integer, List<SampleRow>> byGear = new TreeMap<>();
        for (final SampleRow r : moving) {
            byGear.computeIfAbsent(r.gear(), g -> new ArrayList<>()).add(r);
        }
        final Map<String, Object> gears = new LinkedHashMap<>();
        byGear.forEach((gear, list) -> gears.put(String.valueOf(gear), map(
                "timePct", pct(list.size(), moving.size()),
                "rpmP50", round(percentile(list.stream().mapToDouble(SampleRow::rpm).toArray(), 0.5), 0),
                "limiterWithThrottlePct", pct(list.stream().filter(r -> r.rpm() >= limiterRpm && r.accel() > 200).count(), list.size()))));

        return map(
                "maxRpm", round(meta.engineMaxRpm(), 0),
                "peakPowerHp", round(peakPower.power() / WATTS_PER_HP, 1),
                "peakPowerRpm", round(peakPower.rpm(), 0),
                "peakTorqueNm", round(peakTorque.torque(), 1),
                "peakTorqueRpm", round(peakTorque.rpm(), 0),
                "boostMaxPsi", round(rows.stream().mapToDouble(SampleRow::boost).max().orElse(0.0), 1),
                "gears", gears);
    }

    /** Temperatura em °F (unidade do jogo); desgaste final só no Forza Motorsport. */
    private Map<String, Object> tires(final List<SampleRow> rows, final List<SampleRow> moving) {
        final List<SampleRow> basis = moving.isEmpty() ? rows : moving;
        final float[] lastWear = rows.get(rows.size() - 1).tireWear();
        final Map<String, Object> result = new LinkedHashMap<>();
        for (int w = 0; w < 4; w++) {
            final int wheel = w;
            final double[] temps = basis.stream().mapToDouble(r -> r.tireTemp()[wheel]).toArray();
            final Map<String, Object> tire = map("tempMeanF", round(mean(temps), 1), "tempP95F", round(percentile(temps, 0.95), 1));
            if (lastWear != null) {
                tire.put("wearFinal", round(lastWear[w], 3));
            }
            result.put(WHEELS[w], tire);
        }
        return result;
    }

    private Map<String, Object> balance(final List<SampleRow> sel) {
        if (sel.isEmpty()) {
            return map("samples", 0);
        }
        int under = 0;
        int over = 0;
        double frontSum = 0;
        double rearSum = 0;
        for (final SampleRow r : sel) {
            final double front = (Math.abs(r.slipAngle()[0]) + Math.abs(r.slipAngle()[1])) / 2.0;
            final double rear = (Math.abs(r.slipAngle()[2]) + Math.abs(r.slipAngle()[3])) / 2.0;
            frontSum += front;
            rearSum += rear;
            final double diff = front - rear;
            if (diff > BALANCE_THRESHOLD) {
                under++;
            } else if (diff < -BALANCE_THRESHOLD) {
                over++;
            }
        }
        return map(
                "samples", sel.size(),
                "frontSlipAngleMean", round(frontSum / sel.size(), 2),
                "rearSlipAngleMean", round(rearSum / sel.size(), 2),
                "understeerPct", pct(under, sel.size()),
                "oversteerPct", pct(over, sel.size()));
    }

    private Map<String, Object> braking(final List<SampleRow> moving) {
        final List<SampleRow> sel = moving.stream().filter(r -> r.brake() > 150).toList();
        final long front = sel.stream().filter(r -> Math.max(Math.abs(r.slipRatio()[0]), Math.abs(r.slipRatio()[1])) > 1.0).count();
        final long rear = sel.stream().filter(r -> Math.max(Math.abs(r.slipRatio()[2]), Math.abs(r.slipRatio()[3])) > 1.0).count();
        return map("samples", sel.size(), "frontLockPct", pct(front, sel.size()), "rearLockPct", pct(rear, sel.size()));
    }

    private Map<String, Object> traction(final SessionMeta meta, final List<SampleRow> moving) {
        final int[] driven = switch (meta.drivetrain()) {
            case 0 -> new int[]{0, 1};
            case 1 -> new int[]{2, 3};
            default -> new int[]{0, 1, 2, 3};
        };
        final List<SampleRow> sel = moving.stream().filter(r -> r.accel() > 200 && r.brake() < 20).toList();
        final Predicate<SampleRow> spinning = r -> {
            for (final int wheel : driven) {
                if (Math.abs(r.slipRatio()[wheel]) > 1.0) {
                    return true;
                }
            }
            return false;
        };
        final Map<Integer, List<SampleRow>> byGear = new TreeMap<>();
        for (final SampleRow r : sel) {
            byGear.computeIfAbsent(r.gear(), g -> new ArrayList<>()).add(r);
        }
        final Map<String, Object> perGear = new LinkedHashMap<>();
        byGear.forEach((gear, list) -> perGear.put(String.valueOf(gear), pct(list.stream().filter(spinning).count(), list.size())));
        return map(
                "samples", sel.size(),
                "drivenWheelSpinPct", pct(sel.stream().filter(spinning).count(), sel.size()),
                "spinPctByGear", perGear);
    }

    private Map<String, Object> lapsSummary(final List<LapRecord> laps) {
        final List<Map<String, Object>> list = laps.stream()
                .map(l -> map("lap", l.lapNumber(), "timeS", round(l.lapTimeS(), 3)))
                .toList();
        return map(
                "times", list,
                "bestS", laps.isEmpty() ? null : round(laps.stream().mapToDouble(LapRecord::lapTimeS).min().orElse(0.0), 3));
    }

    private static boolean cornering(final SampleRow r) {
        return Math.abs(r.steer()) > 25;
    }

    private static double mean(final List<SampleRow> rows, final ToDoubleFunction<SampleRow> fn) {
        return rows.isEmpty() ? 0.0 : rows.stream().mapToDouble(fn).average().orElse(0.0);
    }

    private static double mean(final double[] values) {
        double sum = 0;
        for (final double v : values) {
            sum += v;
        }
        return values.length == 0 ? 0.0 : sum / values.length;
    }

    private static double percentile(final double[] values, final double p) {
        if (values.length == 0) {
            return 0.0;
        }
        final double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) (p * sorted.length))];
    }

    private static long count(final double[] values, final java.util.function.DoublePredicate predicate) {
        long total = 0;
        for (final double v : values) {
            if (predicate.test(v)) {
                total++;
            }
        }
        return total;
    }

    private static double pct(final long part, final long total) {
        return total == 0 ? 0.0 : round(100.0 * part / total, 1);
    }

    private static double round(final double value, final int decimals) {
        final double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    /** LinkedHashMap a partir de pares chave/valor — mantém a ordem de declaração no JSON. */
    private static Map<String, Object> map(final Object... keyValues) {
        final Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put((String) keyValues[i], keyValues[i + 1]);
        }
        return result;
    }
}
