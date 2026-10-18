package cl.andesstay.catalog.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Traduce el cuerpo de un mensaje AMQP al {@link ReservationEventEnvelope} del contrato y
 * comprueba que venga completo.
 *
 * <p>Está separado del consumidor a propósito: así el {@code @RabbitListener} queda limitado a
 * coordinar (ACK / NACK / reintento) y toda la decisión de "este mensaje es basura" vive en un
 * solo lugar, probable sin broker.</p>
 *
 * <p>¿Por qué se parsea a mano en vez de usar un {@code Jackson2JsonMessageConverter} en la firma
 * del listener? Porque con el converter el JSON malformado explota <em>antes</em> de entrar al
 * método, y el contenedor aplicaría su política por defecto en vez de la del contrato (log ERROR
 * con eventId y traceId + {@code basicNack} sin requeue). Parseando acá, el consumidor controla
 * el ACK de todos los casos.</p>
 *
 * <p>Esta clase valida la <strong>forma</strong> del mensaje, no el negocio: que el
 * {@code type} corresponda a un evento que mueva stock, que la unidad exista y que quede
 * disponibilidad lo decide {@code UnitAvailabilityService}, que es el dueño de esa regla.</p>
 */
@Component
public class ReservationEventParser {

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper el mapper de Spring Boot, que ya trae el módulo de java.time (para
     *                     {@code Instant} y {@code LocalDate} en ISO-8601) y que ignora las
     *                     propiedades desconocidas: si el publicador agrega un campo nuevo al
     *                     envelope, los mensajes NO se caen a la DLQ por eso.
     */
    public ReservationEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parsea y valida el cuerpo del mensaje.
     *
     * @param body cuerpo del mensaje (JSON UTF-8)
     * @return el envelope con los campos que el catálogo necesita para operar
     * @throws InvalidEventException si el JSON es inválido o el envelope está incompleto: los
     *                               errores NO recuperables del contrato que se descartan sin
     *                               reintentar
     */
    public ReservationEventEnvelope parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw new InvalidEventException("El mensaje llegó sin cuerpo");
        }
        ReservationEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(body, ReservationEventEnvelope.class);
        } catch (JsonProcessingException ex) {
            // El cuerpo se recorta en el log: un mensaje enorme no debe inundar la salida.
            throw new InvalidEventException("JSON inválido: " + ex.getOriginalMessage()
                    + " · cuerpo=" + preview(body), ex);
        } catch (Exception ex) {
            throw new InvalidEventException("No se pudo leer el cuerpo del mensaje: "
                    + ex.getMessage(), ex);
        }
        validate(envelope);
        return envelope;
    }

    /**
     * Completitud del envelope. Se exige SOLO lo que el catálogo necesita para mover el stock de
     * forma segura: identidad del mensaje ({@code eventId}, que es la llave de la idempotencia),
     * el {@code type} (que decide si descuenta o libera), la unidad y el código de la reserva.
     *
     * <p>{@code unitName}, {@code status}, las fechas y el monto pueden venir nulos: son para el
     * log o no se usan, y mandar a la DLQ un evento perfectamente aplicable porque le falta un
     * campo decorativo dejaría el stock desalineado con las reservas, que es el problema que
     * esta historia viene a resolver.</p>
     */
    private void validate(ReservationEventEnvelope envelope) {
        if (envelope == null) {
            throw new InvalidEventException("El cuerpo del mensaje es un JSON nulo");
        }
        requireText(envelope.eventId(), "eventId", envelope);
        requireText(envelope.type(), "type", envelope);

        ReservationEventPayload payload = envelope.payload();
        if (payload == null) {
            throw new InvalidEventException(describe(envelope) + " · envelope incompleto: "
                    + "falta 'payload'");
        }
        requireText(payload.reservationCode(), "payload.reservationCode", envelope);
        if (payload.unitId() == null) {
            throw new InvalidEventException(describe(envelope) + " · envelope incompleto: "
                    + "falta 'payload.unitId', que es la unidad cuyo stock habría que mover");
        }
    }

    private void requireText(String value, String field, ReservationEventEnvelope envelope) {
        if (value == null || value.isBlank()) {
            throw new InvalidEventException(describe(envelope) + " · envelope incompleto: falta '"
                    + field + "'");
        }
    }

    /**
     * Identificación del evento para los logs de error.
     *
     * <p>Lleva los datos que exige el contrato de la EP3 para un descarte a la DLQ:
     * {@code eventId}, {@code traceId} y (en el texto de la excepción) el motivo. El
     * {@code traceId} es justo el que permite seguir el evento en los logs de reservations y de
     * report, que es lo primero que se necesita cuando algo cae en {@code q.evt.catalog.dlq}: sin
     * él, un mensaje descartado no se puede cruzar con la operación que lo originó.</p>
     *
     * <p>Se imprime incluso cuando viene nulo (p. ej. {@code eventId=null}): saber que el
     * publicador NO lo mandó es parte del diagnóstico.</p>
     */
    private String describe(ReservationEventEnvelope envelope) {
        return "type=" + envelope.type() + " eventId=" + envelope.eventId()
                + " traceId=" + envelope.traceId();
    }

    private String preview(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
