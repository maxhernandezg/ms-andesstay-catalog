package cl.andesstay.catalog.messaging;

import java.time.Instant;

/**
 * Envelope común del contrato de mensajería de la EP3: es el cuerpo JSON de TODO mensaje que
 * viaja por {@code events.topic}.
 *
 * <pre>
 * {
 *   "type": "reservation.confirmed",
 *   "eventId": "6f1c...-uuid",
 *   "timestamp": "2026-10-17T20:15:30Z",
 *   "traceId": "9c94d6ef",
 *   "correlationId": "RSV-000002",
 *   "actor": "max.hernandezg@duocuc.cl",
 *   "payload": { ... }
 * }
 * </pre>
 *
 * <p>Es un record inmutable y <strong>sin lógica</strong>: solo transporta lo que llegó. La
 * validación de completitud vive en {@link ReservationEventParser} y el efecto sobre la
 * disponibilidad en {@code UnitAvailabilityService}.</p>
 *
 * <p>Los campos que este servicio no usa para decidir ({@code traceId}, {@code correlationId},
 * {@code actor}) se mapean igual porque van a los logs: sin {@code traceId} no se puede seguir un
 * evento entre reservations, catalog, report y audit, que es justo lo que se necesita cuando algo
 * cae en {@code q.evt.catalog.dlq}. El {@code actor} es informativo: la mensajería NO es una
 * puerta de atrás a la autorización y acá nadie decide permisos con él.</p>
 *
 * @param type          tipo del evento; para esta cola, {@code reservation.confirmed} o
 *                      {@code reservation.cancelled}
 * @param eventId       identificador único del mensaje; es la clave de la idempotencia
 * @param timestamp     momento en que el publicador emitió el evento
 * @param traceId       correlación de la traza entre microservicios
 * @param correlationId correlación de negocio (el código de la reserva)
 * @param actor         usuario que provocó el cambio ({@code preferred_username} del token)
 * @param payload       datos de la reserva
 */
public record ReservationEventEnvelope(
        String type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        String actor,
        ReservationEventPayload payload) {
}
