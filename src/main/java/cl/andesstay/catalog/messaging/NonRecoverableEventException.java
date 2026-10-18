package cl.andesstay.catalog.messaging;

/**
 * Error NO recuperable de un mensaje: reintentarlo daría exactamente el mismo resultado, así que
 * el consumidor lo manda derecho a la DLQ con {@code basicNack(deliveryTag, false, false)}.
 *
 * <p>Es la raíz de los dos motivos que define el contrato de la EP3 para este servicio:</p>
 * <ul>
 *   <li>{@link InvalidEventException}: el mensaje está mal formado (JSON inválido o envelope
 *       incompleto);</li>
 *   <li>esta misma clase, lanzada por el dominio cuando el <em>dato de negocio es imposible</em>:
 *       la unidad del evento no existe en el catálogo, o se pide confirmar una reserva sobre una
 *       unidad que ya está en cero. No se deja el stock negativo ni se reintenta: alguien tiene
 *       que mirar por qué se confirmó una reserva sin cupo.</li>
 * </ul>
 *
 * <p>Que las dos cuelguen de la misma raíz es lo que le permite al consumidor tener UN solo
 * {@code catch} para "a la DLQ sin reintentar" y otro para "error recuperable, reintentar": la
 * política de ACK no se enreda en una cadena de {@code instanceof}.</p>
 */
public class NonRecoverableEventException extends RuntimeException {

    public NonRecoverableEventException(String message) {
        super(message);
    }

    public NonRecoverableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
