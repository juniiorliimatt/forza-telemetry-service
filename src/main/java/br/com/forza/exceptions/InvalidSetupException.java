package br.com.forza.exceptions;

import java.util.List;

/** Setup fora dos limites do jogo ou incompleto: 422 com a lista de erros. */
public class InvalidSetupException extends RuntimeException {

    private final transient List<String> errors;

    public InvalidSetupException(final List<String> errors) {
        super("Setup inválido: " + String.join(" ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
