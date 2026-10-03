package br.com.forza.exceptions;

public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException() {
        super("Cursor de paginação inválido");
    }
}
