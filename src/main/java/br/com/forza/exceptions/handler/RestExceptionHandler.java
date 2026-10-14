package br.com.forza.exceptions.handler;

import br.com.forza.exceptions.InvalidCursorException;
import br.com.forza.exceptions.InvalidSetupException;
import br.com.forza.exceptions.ResourceNotFoundException;
import br.com.forza.exceptions.SetupConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Erros no formato Problem Details (RFC 9457, {@code application/problem+json}). Estende
 * {@link ResponseEntityExceptionHandler} pra que exceções do próprio MVC (método não
 * suportado, parâmetro ausente…) mantenham o status correto, e o catch-all abaixo nunca
 * devolve stack trace nem detalhe interno.
 */
@RestControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(final ResourceNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(InvalidCursorException.class)
    public ProblemDetail handleInvalidCursor(final InvalidCursorException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(InvalidSetupException.class)
    public ProblemDetail handleInvalidSetup(final InvalidSetupException exception) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
        problem.setProperty("errors", exception.errors());
        return problem;
    }

    @ExceptionHandler(SetupConflictException.class)
    public ProblemDetail handleSetupConflict(final SetupConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(final MethodArgumentTypeMismatchException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Valor inválido para o parâmetro '" + exception.getName() + "'");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(final Exception exception) {
        logger.error("Erro inesperado", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno do servidor");
    }
}
