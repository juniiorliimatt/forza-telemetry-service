package br.com.forza.exceptions;

/** A operação não cabe no estado atual do setup (ex.: aplicar ajustes sem setup aceito): 409. */
public class SetupConflictException extends RuntimeException {

    public SetupConflictException(final String message) {
        super(message);
    }
}
