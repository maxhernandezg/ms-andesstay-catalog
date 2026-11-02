package cl.andesstay.catalog.service;

import cl.andesstay.catalog.config.AndesStayRabbitProperties;
import cl.andesstay.catalog.domain.ProcessedEvent;
import cl.andesstay.catalog.domain.Unit;
import cl.andesstay.catalog.messaging.NonRecoverableEventException;
import cl.andesstay.catalog.messaging.ReservationEventEnvelope;
import cl.andesstay.catalog.messaging.ReservationEventPayload;
import cl.andesstay.catalog.repository.ProcessedEventRepository;
import cl.andesstay.catalog.repository.UnitRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regla del caso que faltaba: <strong>la disponibilidad baja al confirmar la reserva y se libera
 * al cancelar</strong>.
 *
 * <p>Se hace por mensajería: ms-andesstay-reservations publica el hecho en {@code events.topic} y
 * este servicio lo aplica al leer {@code q.evt.catalog}. Los dos microservicios siguen
 * desacoplados —reservas no llama al catálogo y ni sabe que esta cola existe—, y si el catálogo
 * está caído cuando se confirma una reserva, el evento espera en la cola en vez de hacer fallar
 * la confirmación.</p>
 *
 * <p>Acá vive TODA la regla de negocio; el {@code @RabbitListener} solo coordina el ACK. Eso
 * permite probar esta clase sin broker y, si mañana el mismo movimiento llega por otro
 * transporte, se reutiliza sin tocar nada.</p>
 *
 * <h2>Qué hace con cada evento</h2>
 * <table>
 *   <tr><th>Evento</th><th>Efecto</th><th>Si no se puede</th></tr>
 *   <tr><td>{@code reservation.confirmed}</td><td>{@code availableStock - 1}</td>
 *       <td>sin cupo: log ERROR y a la DLQ, el stock NO queda negativo</td></tr>
 *   <tr><td>{@code reservation.cancelled}</td><td>{@code availableStock + 1}, topado en
 *       {@code totalStock}, sólo si la reserva había descontado cupo ({@code previousStatus}
 *       distinto de {@code CREADA})</td><td>nunca se confirmó: ACK sin mover el stock; ya
 *       estaba full: ACK con log WARN, no es un error</td></tr>
 * </table>
 *
 * <h2>Transacción</h2>
 * <p>El movimiento de stock y el registro de idempotencia se guardan en la MISMA transacción: si
 * falla el commit no queda el evento marcado como procesado, así el reintento lo vuelve a
 * aplicar; y si el evento queda marcado, el stock ya se movió.</p>
 */
@Service
public class UnitAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(UnitAvailabilityService.class);

    /** Estado de una reserva que todavía no descontó cupo (el descuento llega al confirmar). */
    private static final String NOT_YET_CONFIRMED = "CREADA";

    private final UnitRepository units;
    private final ProcessedEventRepository processedEvents;
    private final AndesStayRabbitProperties props;

    public UnitAvailabilityService(UnitRepository units,
                                   ProcessedEventRepository processedEvents,
                                   AndesStayRabbitProperties props) {
        this.units = units;
        this.processedEvents = processedEvents;
        this.props = props;
    }

    /**
     * Aplica el evento a la disponibilidad de la unidad.
     *
     * @param event envelope ya parseado y validado por {@code ReservationEventParser}
     * @return {@link AvailabilityOutcome#DUPLICATE} si el {@code eventId} ya se había procesado
     *         (sin repetir el efecto), o el movimiento que se hizo
     * @throws NonRecoverableEventException si el dato de negocio es imposible: un tipo de evento
     *                                      que no mueve stock, una unidad que no existe en el
     *                                      catálogo o una confirmación sin disponibilidad. El
     *                                      consumidor lo manda a la DLQ sin reintentar.
     */
    @Transactional
    public AvailabilityOutcome apply(ReservationEventEnvelope event) {
        // 1) El tipo decide el efecto, y se compara contra los nombres del contrato que vienen del
        //    application.yml (nada escrito a mano acá). Se chequea ANTES de tocar la base: un
        //    mensaje que no corresponde a esta cola no merece ni una consulta.
        String type = event.type();
        String confirmed = props.routingKeys().reservationConfirmed();
        String cancelled = props.routingKeys().reservationCancelled();
        if (!confirmed.equals(type) && !cancelled.equals(type)) {
            // q.evt.catalog está atada SOLO a reservation.confirmed y reservation.cancelled, así
            // que esto no puede llegar por los bindings del contrato: o alguien publicó directo en
            // la cola, o se le agregó un binding que nadie acordó. Va a la DLQ para que se revise.
            throw new NonRecoverableEventException("type '" + type + "' no mueve la "
                    + "disponibilidad: esta cola solo acepta '" + confirmed + "' y '" + cancelled
                    + "' · reserva=" + event.payload().reservationCode());
        }

        // 2) Idempotencia: RabbitMQ entrega "al menos una vez" y descontar dos veces el mismo
        //    confirmed dejaría el catálogo mintiendo. La clave primaria de CATALOG_PROCESSED_EVENT
        //    es el eventId: el existsById cubre el caso normal y, si dos entregas simultáneas
        //    pasaran el chequeo a la vez, el INSERT de la segunda choca con la PK y el error se
        //    trata como recuperable; al reintentar, este existsById ya devuelve true y el mensaje
        //    termina en ACK sin mover el stock de nuevo.
        if (processedEvents.existsById(event.eventId())) {
            return AvailabilityOutcome.DUPLICATE;
        }

        ReservationEventPayload payload = event.payload();
        Long unitId = payload.unitId();
        // Se lee la unidad para poder distinguir "no existe" de "no queda cupo" y para dejar en el
        // log el stock anterior. El movimiento NO se hace sobre esta copia: eso sería el
        // read-modify-write que el UPDATE condicional del repositorio viene a evitar.
        Unit unit = units.findById(unitId).orElseThrow(() -> new NonRecoverableEventException(
                "La unidad " + unitId + " del evento no existe en el catálogo · reserva="
                        + payload.reservationCode()));
        int previousStock = unit.getAvailableStock();
        Instant now = Instant.now();

        AvailabilityOutcome outcome = confirmed.equals(type)
                ? reserve(unit, payload, previousStock, now)
                : release(unit, payload, previousStock, now);

        processedEvents.save(new ProcessedEvent(event.eventId(), type, payload.reservationCode(),
                unitId, outcome.name(), now));
        return outcome;
    }

    /** {@code reservation.confirmed}: un cupo menos, nunca bajo cero. */
    private AvailabilityOutcome reserve(Unit unit, ReservationEventPayload payload,
                                        int previousStock, Instant now) {
        if (units.reserveOneIfAvailable(unit.getId(), now) == 0) {
            // La unidad existe, así que la única razón para no haber escrito es que el stock ya
            // está en 0. Es un problema de NEGOCIO, no de infraestructura: se confirmó una reserva
            // sobre una unidad sin cupos. No se deja el stock negativo ni se reintenta (reintentar
            // no va a crear disponibilidad); el mensaje queda en q.evt.catalog.dlq con su eventId
            // y su traceId para que alguien lo mire y decida.
            throw new NonRecoverableEventException("sin disponibilidad para confirmar la reserva "
                    + payload.reservationCode() + ": la unidad " + unit.getId() + " ("
                    + unit.getName() + ") está en 0 de " + unit.getTotalStock()
                    + " cupos. El stock NO se deja negativo; el evento va a la DLQ");
        }
        log.info("Disponibilidad descontada: unidad={} ({}) {} -> {} cupos · reserva={}",
                unit.getId(), unit.getName(), previousStock, previousStock - 1,
                payload.reservationCode());
        return AvailabilityOutcome.RESERVED;
    }

    /** {@code reservation.cancelled}: un cupo más, sin pasarse del stock total. */
    private AvailabilityOutcome release(Unit unit, ReservationEventPayload payload,
                                        int previousStock, Instant now) {
        // Sólo descuenta cupo reservation.confirmed, así que una reserva cancelada sin haberse
        // confirmado (CREADA → CANCELADA) no tiene nada que devolver. reservations publica
        // reservation.cancelled igual en ese caso; si se liberara un cupo, la unidad quedaría con
        // más disponibilidad que la real cada vez que otra reserva confirmada ya ocupa uno.
        if (NOT_YET_CONFIRMED.equals(payload.previousStatus())) {
            log.info("Cancelación de una reserva que nunca se confirmó: no se devuelve cupo · "
                            + "unidad {} ({}) sigue con {} cupos · reserva={}", unit.getId(),
                    unit.getName(), previousStock, payload.reservationCode());
            return AvailabilityOutcome.NEVER_RESERVED;
        }
        if (units.releaseOneIfNotFull(unit.getId(), now) == 0) {
            // La unidad ya tiene todos sus cupos libres: no hay nada que devolver. Con el filtro
            // de arriba no debería pasar, salvo que el stock se haya corregido a mano. No es un
            // error —el tope de TOTAL_STOCK es justo lo que impide inventar disponibilidad— así
            // que se confirma el mensaje y queda el WARN.
            log.warn("Cancelación sin cupo que devolver: la unidad {} ({}) ya tiene sus {} cupos "
                            + "libres · reserva={} (estado anterior {})", unit.getId(),
                    unit.getName(), unit.getTotalStock(), payload.reservationCode(),
                    payload.previousStatus());
            return AvailabilityOutcome.RELEASE_SKIPPED;
        }
        log.info("Disponibilidad liberada: unidad={} ({}) {} -> {} cupos · reserva={}",
                unit.getId(), unit.getName(), previousStock, previousStock + 1,
                payload.reservationCode());
        return AvailabilityOutcome.RELEASED;
    }
}
