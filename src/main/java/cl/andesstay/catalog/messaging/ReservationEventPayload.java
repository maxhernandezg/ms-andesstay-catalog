package cl.andesstay.catalog.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code payload} del envelope: el estado de la reserva después del cambio que generó el evento.
 *
 * <pre>
 * "payload": {
 *   "reservationCode": "RSV-000002", "unitId": 1, "unitName": "Cabaña Espolón",
 *   "guestEmail": "ia.reyes@duocuc.cl", "guestName": "Ian Reyes", "status": "CONFIRMADA",
 *   "previousStatus": "CREADA", "checkIn": "2026-09-20", "checkOut": "2026-09-22", "nights": 2,
 *   "totalAmount": 236000, "occurredAt": "2026-10-17T20:15:30Z"
 * }
 * </pre>
 *
 * <p>El catálogo solo necesita <strong>dos</strong> campos para hacer su trabajo:
 * {@code unitId} (qué unidad mueve el stock) y {@code reservationCode} (para el log y la
 * bitácora de idempotencia). El resto se mapea porque el envelope es del contrato y porque
 * {@code unitName} hace legible el log; nada de esto se persiste. En particular
 * {@code guestEmail} y {@code guestName} son datos personales que acá no se guardan: el catálogo
 * no es una copia de la reserva.</p>
 *
 * <p>Que el evento traiga todo esto es lo que permite descontar la disponibilidad
 * <em>sin llamar de vuelta</em> a ms-andesstay-reservations: un evento que obliga al consumidor
 * a pedir datos por REST no desacopla nada.</p>
 *
 * @param reservationCode código de la reserva ({@code RSV-000002}); obligatorio
 * @param unitId          id de la unidad de este catálogo; obligatorio, es el que mueve el stock
 * @param unitName        nombre de la unidad, solo para los logs
 * @param guestEmail      correo del huésped; NO se persiste
 * @param guestName       nombre del huésped; NO se persiste
 * @param status          estado nuevo de la reserva (informativo: el efecto lo decide el type)
 * @param previousStatus  estado anterior: si es {@code CREADA}, la cancelación no devuelve cupo
 *                        porque la reserva nunca lo descontó
 * @param checkIn         fecha de llegada
 * @param checkOut        fecha de salida
 * @param nights          noches de la estadía
 * @param totalAmount     monto total de la reserva
 * @param occurredAt      momento del cambio de estado
 */
public record ReservationEventPayload(
        String reservationCode,
        Long unitId,
        String unitName,
        String guestEmail,
        String guestName,
        String status,
        String previousStatus,
        LocalDate checkIn,
        LocalDate checkOut,
        Integer nights,
        BigDecimal totalAmount,
        Instant occurredAt) {
}
