package cl.andesstay.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Bitácora de idempotencia del consumidor de eventos (tabla {@code CATALOG_PROCESSED_EVENT}).
 *
 * <p>RabbitMQ garantiza <em>at least once</em>: el mismo evento puede llegar dos veces (el
 * publicador reintentó, el consumidor murió entre el commit y el ACK, o el mensaje se republicó
 * por un error recuperable). Y acá el efecto NO es idempotente por naturaleza: aplicar dos veces
 * un {@code reservation.confirmed} descontaría dos cupos por una sola reserva, que es
 * exactamente el tipo de error que nadie nota hasta que el catálogo miente.</p>
 *
 * <p>Por eso, antes de tocar {@link Unit#getAvailableStock()} se registra acá el
 * {@code eventId} del envelope: como es la <strong>clave primaria</strong>, la propia base de
 * datos impide aplicar dos veces el mismo evento. Si ya está, el consumidor hace ACK y no repite
 * el efecto.</p>
 *
 * <p>Se guarda en la MISMA transacción que el cambio de stock: si el commit falla, el evento no
 * queda marcado como procesado y el reintento lo vuelve a aplicar. Si fueran dos transacciones,
 * una caída entre ambas dejaría el stock movido y el evento "sin procesar" (se descontaría de
 * nuevo) o el evento marcado y el stock intacto (se perdería el descuento).</p>
 */
@Entity
@Table(name = "CATALOG_PROCESSED_EVENT",
        indexes = {
                @Index(name = "IX_CAT_PROC_EVENT_CODE", columnList = "RESERVATION_CODE"),
                @Index(name = "IX_CAT_PROC_EVENT_UNIT", columnList = "UNIT_ID")
        })
public class ProcessedEvent {

    /** {@code eventId} del envelope (también viaja como {@code messageId} de AMQP). */
    @Id
    @Column(name = "EVENT_ID", nullable = false, length = 40)
    private String eventId;

    /** Tipo del evento tal como llegó: {@code reservation.confirmed} o {@code .cancelled}. */
    @Column(name = "EVENT_TYPE", nullable = false, length = 60)
    private String eventType;

    /** Reserva que provocó el movimiento ({@code RSV-000002}). */
    @Column(name = "RESERVATION_CODE", nullable = false, length = 20)
    private String reservationCode;

    /** Unidad cuyo stock se movió. */
    @Column(name = "UNIT_ID", nullable = false)
    private Long unitId;

    /**
     * Qué se hizo con el evento ({@code RESERVED}, {@code RELEASED}, {@code RELEASE_SKIPPED}).
     * No es decorativo: cuando alguien pregunte "¿por qué esta unidad tiene 3 cupos libres?",
     * esta columna es la única forma de distinguir una cancelación que devolvió un cupo de una
     * que no tenía nada que devolver.
     */
    @Column(name = "OUTCOME", nullable = false, length = 20)
    private String outcome;

    /** Momento en que ESTE servicio lo procesó (no el del evento). */
    @Column(name = "PROCESSED_AT", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // Requerido por JPA.
    }

    public ProcessedEvent(String eventId, String eventType, String reservationCode, Long unitId,
                          String outcome, Instant processedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.reservationCode = reservationCode;
        this.unitId = unitId;
        this.outcome = outcome;
        this.processedAt = processedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getReservationCode() {
        return reservationCode;
    }

    public Long getUnitId() {
        return unitId;
    }

    public String getOutcome() {
        return outcome;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
