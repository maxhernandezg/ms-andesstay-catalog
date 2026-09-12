package cl.andesstay.catalog.exception;

/**
 * Conflicto de negocio (sin disponibilidad, codigo duplicado): se traduce a HTTP 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
