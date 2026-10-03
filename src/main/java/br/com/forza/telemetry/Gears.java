package br.com.forza.telemetry;

/**
 * Marchas de tração: 1 a 10 (as transmissões de corrida têm até 10 marchas). O Data Out reporta ré como 0 e
 * neutro/troca de marcha como 11 (visto em dados reais: 11 aparece em movimento, com acelerador solto) — nenhum
 * dos dois é "a maior marcha" nem entra em estatística de câmbio/tração.
 */
public final class Gears {

    public static final int MAX_FORWARD = 10;

    private Gears() {
    }

    public static boolean isForward(final int gear) {
        return gear >= 1 && gear <= MAX_FORWARD;
    }

    /** Chave de mapa de resumo (a marcha como texto); entrada inválida não é marcha de tração. */
    public static boolean isForward(final String gear) {
        try {
            return isForward(Integer.parseInt(gear));
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
