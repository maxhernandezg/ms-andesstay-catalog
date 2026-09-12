package cl.andesstay.catalog.web;

import cl.andesstay.catalog.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * Construye el JSON de error del contrato (seccion 4) de forma identica en el
 * @RestControllerAdvice y en los handlers de Spring Security.
 */
public final class ApiErrorSupport {

    /** Header opcional para propagar el identificador de traza desde el BFF. */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private ApiErrorSupport() {
    }

    public static ApiError build(HttpStatus status, String message, String path, String traceId) {
        return build(status, message, path, traceId, null);
    }

    public static ApiError build(HttpStatus status, String message, String path, String traceId,
                                 List<String> details) {
        return new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path,
                traceId,
                details == null || details.isEmpty() ? null : details);
    }

    /** Usa el trace id que venga del BFF; si no viene, genera uno. */
    public static String traceId(HttpServletRequest request) {
        String header = request != null ? request.getHeader(TRACE_ID_HEADER) : null;
        return StringUtils.hasText(header) ? header : UUID.randomUUID().toString();
    }

    public static String path(HttpServletRequest request) {
        return request != null ? request.getRequestURI() : "";
    }
}
