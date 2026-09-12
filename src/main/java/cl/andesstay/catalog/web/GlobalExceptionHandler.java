package cl.andesstay.catalog.web;

import cl.andesstay.catalog.dto.ApiError;
import cl.andesstay.catalog.exception.ConflictException;
import cl.andesstay.catalog.exception.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduce las excepciones al formato de error unico del contrato (seccion 4).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 400: falla la validacion de Bean Validation. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return response(HttpStatus.BAD_REQUEST, "Los datos enviados no son validos", request, details);
    }

    /** 400: JSON mal formado o parametro de tipo incorrecto (por ejemplo type=CASA). */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "La peticion no se pudo interpretar: " + ex.getMessage(),
                request, null);
    }

    /** 404: la unidad no existe. */
    @ExceptionHandler({NotFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    /** 409: sin disponibilidad, codigo duplicado o choque de concurrencia. */
    @ExceptionHandler({ConflictException.class, DataIntegrityViolationException.class,
            OptimisticLockingFailureException.class})
    public ResponseEntity<ApiError> handleConflict(Exception ex, HttpServletRequest request) {
        String message = ex instanceof ConflictException
                ? ex.getMessage()
                : "La operacion choca con el estado actual de los datos";
        return response(HttpStatus.CONFLICT, message, request, null);
    }

    /** 502: no se pudo hablar con un servicio aguas abajo. */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiError> handleBadGateway(RestClientException ex, HttpServletRequest request) {
        log.warn("Error hablando con un servicio aguas abajo: {}", ex.getMessage());
        return response(HttpStatus.BAD_GATEWAY, "No fue posible contactar a un servicio dependiente",
                request, null);
    }

    /** 403: si la denegacion se produce dentro del controlador (method security). */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "No tienes el rol necesario para ejecutar esta operacion",
                request, null);
    }

    /** 500: cualquier otra cosa. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Error inesperado en {}", ApiErrorSupport.path(request), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servicio de catalogo",
                request, null);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String message,
                                              HttpServletRequest request, List<String> details) {
        ApiError body = ApiErrorSupport.build(status, message, ApiErrorSupport.path(request),
                ApiErrorSupport.traceId(request), details);
        return ResponseEntity.status(status).body(body);
    }
}
