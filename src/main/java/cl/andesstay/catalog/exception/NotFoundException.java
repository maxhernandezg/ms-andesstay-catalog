package cl.andesstay.catalog.exception;

/**
 * Recurso inexistente: se traduce a HTTP 404 en el GlobalExceptionHandler.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
