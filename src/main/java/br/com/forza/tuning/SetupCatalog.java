package br.com.forza.tuning;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parâmetros do setup que o usuário informa por carro, com os limites que o jogo aceita (mín/máx dos sliders). Os limites
 * são fixos e iguais para todo carro, exceto molas, altura do solo e aerodinâmica, que variam por carro: para esses o usuário informa o
 * intervalo do slider. Pressão sempre em bar. Os ids de guia são os do menu do jogo (os mesmos de {@link TuningBaseline}).
 * Serve à validação, ao formulário (limites devolvidos pela API) e ao advisor (que trava o valor-alvo entre mín e máx).
 */
public final class SetupCatalog {

    public static final int MAX_GEARS = 10;

    /** Mín/máx do slider de um carro (molas e aerodinâmica). */
    public record Range(double min, double max) {
    }

    /**
     * @param axle FRONT, REAR ou NONE
     * @param min  nulo quando {@code perCar}: vem do intervalo informado pelo usuário
     */
    public record Field(String key, String guide, String label, String axle, String unit, Double min, Double max, boolean perCar) {
    }

    private SetupCatalog() {
    }

    private static Field fixed(final String key, final String guide, final String label, final String axle, final String unit, final double min, final double max) {
        return new Field(key, guide, label, axle, unit, min, max, false);
    }

    private static Field perCar(final String key, final String guide, final String label, final String axle) {
        return new Field(key, guide, label, axle, "", null, null, true);
    }

    /** Todos os campos do setup para um carro com {@code gearCount} marchas, na ordem do menu do jogo. */
    public static List<Field> fields(final int gearCount) {
        final List<Field> out = new ArrayList<>();
        out.add(fixed("tire_pressure_front", "pneus", "Pressão dos pneus dianteiros", "FRONT", "bar", 1.0, 3.8));
        out.add(fixed("tire_pressure_rear", "pneus", "Pressão dos pneus traseiros", "REAR", "bar", 1.0, 3.8));
        out.add(fixed("final_drive", "cambio", "Transmissão final", "NONE", "", 2.20, 6.10));
        for (int gear = 1; gear <= Math.min(gearCount, MAX_GEARS); gear++) {
            out.add(fixed("gear_" + gear, "cambio", "Relação da " + gear + "ª marcha", "NONE", "", 0.48, 6.0));
        }
        out.add(fixed("camber_front", "alinhamento", "Cambagem dianteira", "FRONT", "°", -5.0, 5.0));
        out.add(fixed("camber_rear", "alinhamento", "Cambagem traseira", "REAR", "°", -5.0, 5.0));
        out.add(fixed("toe_front", "alinhamento", "Convergência dianteira", "FRONT", "°", -5.0, 5.0));
        out.add(fixed("toe_rear", "alinhamento", "Convergência traseira", "REAR", "°", -5.0, 5.0));
        out.add(fixed("caster", "alinhamento", "Caster dianteiro", "FRONT", "°", 1.0, 7.0));
        out.add(fixed("arb_front", "barras", "Barra estabilizadora dianteira", "FRONT", "pontos", 1.0, 65.0));
        out.add(fixed("arb_rear", "barras", "Barra estabilizadora traseira", "REAR", "pontos", 1.0, 65.0));
        out.add(perCar("spring_front", "molas", "Mola dianteira", "FRONT"));
        out.add(perCar("spring_rear", "molas", "Mola traseira", "REAR"));
        // Altura do solo: varia por carro e fica sempre no máximo (baixar faz o jogo perder velocidade), por isso o advisor nunca a sugere.
        out.add(perCar("ride_height_front", "molas", "Altura do solo dianteira (sempre no máximo)", "FRONT"));
        out.add(perCar("ride_height_rear", "molas", "Altura do solo traseira (sempre no máximo)", "REAR"));
        out.add(fixed("rebound_front", "amortecimento", "Rigidez de retorno dianteira", "FRONT", "pontos", 1.0, 20.0));
        out.add(fixed("rebound_rear", "amortecimento", "Rigidez de retorno traseira", "REAR", "pontos", 1.0, 20.0));
        out.add(fixed("bump_front", "amortecimento", "Rigidez de compressão dianteira", "FRONT", "pontos", 1.0, 20.0));
        out.add(fixed("bump_rear", "amortecimento", "Rigidez de compressão traseira", "REAR", "pontos", 1.0, 20.0));
        out.add(perCar("downforce_front", "aerodinamica", "Aerodinâmica dianteira", "FRONT"));
        out.add(perCar("downforce_rear", "aerodinamica", "Aerodinâmica traseira", "REAR"));
        out.add(fixed("brake_balance", "freios", "Equilíbrio de freio (0% traseira, 100% dianteira)", "NONE", "%", 0.0, 100.0));
        out.add(fixed("brake_pressure", "freios", "Pressão de freio", "NONE", "%", 0.0, 200.0));
        out.add(fixed("diff_accel_front", "diferencial", "Diferencial dianteiro — aceleração", "FRONT", "%", 0.0, 100.0));
        out.add(fixed("diff_decel_front", "diferencial", "Diferencial dianteiro — desaceleração", "FRONT", "%", 0.0, 100.0));
        out.add(fixed("diff_accel_rear", "diferencial", "Diferencial traseiro — aceleração", "REAR", "%", 0.0, 100.0));
        out.add(fixed("diff_decel_rear", "diferencial", "Diferencial traseiro — desaceleração", "REAR", "%", 0.0, 100.0));
        out.add(fixed("diff_center", "diferencial", "Diferencial central — equilíbrio", "NONE", "%", 0.0, 100.0));
        return out;
    }

    public static Optional<Field> find(final String key, final int gearCount) {
        return fields(gearCount).stream().filter(f -> f.key().equals(key)).findFirst();
    }

    /**
     * Campo do setup que a sugestão do advisor mexe; vazio quando não há campo correspondente (faixa de
     * marchas), caso em que a sugestão segue só com o passo, sem valor-alvo.
     */
    public static Optional<String> keyFor(final String guide, final String parameter, final String axle) {
        final String name = parameter.toLowerCase(Locale.ROOT);
        final String side = "FRONT".equals(axle) ? "front" : "REAR".equals(axle) ? "rear" : null;
        return switch (guide) {
            case "pneus" -> side == null ? Optional.empty() : Optional.of("tire_pressure_" + side);
            case "cambio" -> gearKey(name);
            case "alinhamento" -> name.startsWith("cambagem") && side != null ? Optional.of("camber_" + side)
                    : name.startsWith("convergência") && side != null ? Optional.of("toe_" + side) : Optional.empty();
            case "barras" -> side == null ? Optional.empty() : Optional.of("arb_" + side);
            case "molas" -> name.startsWith("mola") && side != null ? Optional.of("spring_" + side) : Optional.empty();
            case "amortecimento" -> side == null ? Optional.empty()
                    : name.contains("retorno") ? Optional.of("rebound_" + side)
                    : name.contains("compressão") ? Optional.of("bump_" + side) : Optional.empty();
            case "freios" -> name.contains("equilíbrio") ? Optional.of("brake_balance")
                    : name.contains("pressão") ? Optional.of("brake_pressure") : Optional.empty();
            case "diferencial" -> differentialKey(name, side);
            default -> Optional.empty();
        };
    }

    private static Optional<String> gearKey(final String name) {
        if (name.startsWith("relação final")) {
            return Optional.of("final_drive");
        }
        final var matcher = java.util.regex.Pattern.compile("^relação da (\\d+)ª marcha").matcher(name);
        return matcher.find() ? Optional.of("gear_" + matcher.group(1)) : Optional.empty();
    }

    private static Optional<String> differentialKey(final String name, final String side) {
        if (name.contains("central")) {
            return Optional.of("diff_center");
        }
        if (side == null) {
            return Optional.empty();
        }
        // "desaceleração" contém "aceleração": testar a desaceleração primeiro
        return name.contains("desaceleração") ? Optional.of("diff_decel_" + side)
                : name.contains("aceleração") ? Optional.of("diff_accel_" + side) : Optional.empty();
    }

    /** Mensagens de erro (vazio = válido): todos os campos presentes e dentro do limite do jogo / do intervalo do carro. */
    public static List<String> validate(final int gearCount, final Map<String, Double> values, final Map<String, Range> ranges) {
        final List<String> errors = new ArrayList<>();
        if (gearCount < 1 || gearCount > MAX_GEARS) {
            errors.add("Quantidade de marchas deve estar entre 1 e " + MAX_GEARS + ".");
            return errors;
        }
        final List<Field> fields = fields(gearCount);
        final Set<String> known = fields.stream().map(Field::key).collect(Collectors.toSet());
        values.keySet().stream().filter(k -> !known.contains(k)).sorted()
                .forEach(k -> errors.add("Campo desconhecido para este carro: " + k + "."));
        for (final Field field : fields) {
            final Double value = values.get(field.key());
            if (value == null || value.isNaN() || value.isInfinite()) {
                errors.add("Valor obrigatório: " + field.key() + ".");
                continue;
            }
            final double min;
            final double max;
            if (field.perCar()) {
                final Range range = ranges.get(field.key());
                if (range == null) {
                    errors.add("Informe o mínimo e o máximo do slider de " + field.key() + ".");
                    continue;
                }
                if (!(range.min() < range.max())) {
                    errors.add("Intervalo inválido em " + field.key() + ": o mínimo deve ser menor que o máximo.");
                    continue;
                }
                min = range.min();
                max = range.max();
            } else {
                min = field.min();
                max = field.max();
            }
            if (value < min || value > max) {
                errors.add(field.key() + " deve estar entre " + format(min) + " e " + format(max) + ".");
            }
        }
        return errors;
    }

    private static String format(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value).replace('.', ',');
    }
}
