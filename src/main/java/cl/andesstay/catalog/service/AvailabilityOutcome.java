package cl.andesstay.catalog.service;

/**
 * Resultado de aplicar un evento de reserva a la disponibilidad. Lo devuelve
 * {@link UnitAvailabilityService} para que el consumidor pueda loguear distinto SIN tener que
 * preguntarle nada más a la base de datos (y, en particular, para distinguir un reenvío de un
 * movimiento nuevo: los dos terminan en ACK).
 */
public enum AvailabilityOutcome {

    /** {@code reservation.confirmed}: se descontó un cupo de la unidad. */
    RESERVED,

    /** {@code reservation.cancelled}: se devolvió un cupo a la unidad. */
    RELEASED,

    /**
     * {@code reservation.cancelled} sobre una unidad que ya tenía todos sus cupos libres: no hay
     * nada que devolver. Es el caso normal de una reserva cancelada que nunca se confirmó
     * (CREADA → CANCELADA), así que se confirma el mensaje y se deja constancia en el log.
     */
    RELEASE_SKIPPED,

    /** El {@code eventId} ya estaba en CATALOG_PROCESSED_EVENT: no se repitió el efecto. */
    DUPLICATE
}
