package cl.andesstay.catalog.messaging;

/**
 * El mensaje está mal formado: el cuerpo no es JSON válido o al envelope del contrato le falta
 * algo imprescindible ({@code eventId}, {@code type}, {@code payload},
 * {@code payload.reservationCode} o {@code payload.unitId}).
 *
 * <p>Es un {@link NonRecoverableEventException}: el consumidor hace
 * {@code basicNack(deliveryTag, false, false)} y el broker lo dead-letterea a
 * {@code q.evt.catalog.dlq} de inmediato, SIN reintentos, porque reintentar un mensaje corrupto
 * solo gasta la cola. Queda un log ERROR con el {@code eventId}, el {@code traceId} y el motivo
 * para poder repararlo y reinyectarlo a mano.</p>
 */
public class InvalidEventException extends NonRecoverableEventException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
