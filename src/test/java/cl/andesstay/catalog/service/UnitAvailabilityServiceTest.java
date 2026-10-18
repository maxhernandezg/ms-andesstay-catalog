package cl.andesstay.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cl.andesstay.catalog.config.AndesStayRabbitProperties;
import cl.andesstay.catalog.domain.ProcessedEvent;
import cl.andesstay.catalog.domain.Unit;
import cl.andesstay.catalog.domain.UnitType;
import cl.andesstay.catalog.messaging.NonRecoverableEventException;
import cl.andesstay.catalog.messaging.ReservationEventEnvelope;
import cl.andesstay.catalog.messaging.ReservationEventPayload;
import cl.andesstay.catalog.repository.ProcessedEventRepository;
import cl.andesstay.catalog.repository.UnitRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Efecto de los eventos sobre la disponibilidad, contra H2 y SIN broker: el servicio se invoca
 * directo con el envelope del contrato ya parseado, que es justo lo que permite probar la regla
 * de negocio sin levantar RabbitMQ.
 *
 * <p>Casos cubiertos:</p>
 * <ol>
 *   <li>{@code reservation.confirmed} baja la disponibilidad en uno;</li>
 *   <li>{@code reservation.cancelled} la libera;</li>
 *   <li>el MISMO {@code eventId} dos veces deja el stock igual (idempotencia);</li>
 *   <li>sin disponibilidad: error no recuperable (el consumidor lo manda a la DLQ) y el stock
 *       NO queda negativo;</li>
 *   <li>cancelar una unidad que ya está full no se pasa de {@code totalStock};</li>
 *   <li>unidad inexistente y tipo de evento que no mueve stock: también a la DLQ.</li>
 * </ol>
 */
@DataJpaTest
@Import(UnitAvailabilityService.class)
@ActiveProfiles("test")
class UnitAvailabilityServiceTest {

    private static final String CONFIRMED = "reservation.confirmed";
    private static final String CANCELLED = "reservation.cancelled";

    /**
     * Los nombres del contrato tal como los trae el application.yml, pero armados a mano: esta
     * prueba es del EFECTO en el dominio, no del binding de configuración (eso lo verifica
     * RabbitTopologyConfigTest contra el yml real).
     */
    @TestConfiguration
    static class ContractProperties {

        @Bean
        AndesStayRabbitProperties andesStayRabbitProperties() {
            return new AndesStayRabbitProperties(false, 3, "x-attempt",
                    new AndesStayRabbitProperties.Exchanges("events.topic", "events.dead.dlx"),
                    new AndesStayRabbitProperties.Queues("q.evt.catalog", "q.evt.catalog.dlq"),
                    new AndesStayRabbitProperties.RoutingKeys(CONFIRMED, CANCELLED,
                            "catalog.dead"));
        }
    }

    @Autowired
    private UnitAvailabilityService service;

    @Autowired
    private UnitRepository units;

    @Autowired
    private ProcessedEventRepository processedEvents;

    private Long unitId;

    @BeforeEach
    void setUp() {
        processedEvents.deleteAll();
        units.deleteAll();
        // Unidad con 5 cupos totales y 3 libres: hay espacio para descontar y para liberar.
        Unit unit = units.save(new Unit("UN-EVT-001", "Cabana Rio Espolon", UnitType.CABANA,
                "Refugio Futaleufu", "Futaleufu, Los Lagos", 4, new BigDecimal("95000"),
                5, 3, Boolean.TRUE));
        this.unitId = unit.getId();
    }

    @Test
    @DisplayName("reservation.confirmed baja la disponibilidad en uno y queda registrado")
    void confirmedDecrementsAvailability() {
        AvailabilityOutcome outcome = service.apply(event("evt-confirmado-1", CONFIRMED, unitId));

        assertThat(outcome).isEqualTo(AvailabilityOutcome.RESERVED);
        assertThat(stock()).isEqualTo(2);

        // La bitácora de idempotencia queda escrita en la misma transacción que el stock.
        Optional<ProcessedEvent> processed = processedEvents.findById("evt-confirmado-1");
        assertThat(processed).isPresent();
        assertThat(processed.get().getEventType()).isEqualTo(CONFIRMED);
        assertThat(processed.get().getReservationCode()).isEqualTo("RSV-000002");
        assertThat(processed.get().getUnitId()).isEqualTo(unitId);
        assertThat(processed.get().getOutcome()).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("reservation.cancelled libera la disponibilidad")
    void cancelledReleasesAvailability() {
        AvailabilityOutcome outcome = service.apply(event("evt-cancelado-1", CANCELLED, unitId));

        assertThat(outcome).isEqualTo(AvailabilityOutcome.RELEASED);
        assertThat(stock()).isEqualTo(4);
    }

    @Test
    @DisplayName("El mismo eventId dos veces deja el stock igual (idempotencia)")
    void sameEventIdTwiceLeavesStockUntouched() {
        ReservationEventEnvelope sameEvent = event("evt-repetido", CONFIRMED, unitId);

        assertThat(service.apply(sameEvent)).isEqualTo(AvailabilityOutcome.RESERVED);
        assertThat(stock()).isEqualTo(2);

        // Reenvío: RabbitMQ entrega "al menos una vez", así que esto pasa de verdad.
        assertThat(service.apply(sameEvent)).isEqualTo(AvailabilityOutcome.DUPLICATE);
        assertThat(stock()).isEqualTo(2);
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Sin disponibilidad: error no recuperable (a la DLQ) y el stock NO queda negativo")
    void confirmedWithoutStockFailsAndNeverGoesNegative() {
        Unit sinCupos = units.save(new Unit("UN-EVT-002", "Habitacion Puelo", UnitType.HABITACION,
                "Refugio Cochamo", "Cochamo, Los Lagos", 2, new BigDecimal("48000"),
                2, 0, Boolean.TRUE));

        assertThatThrownBy(() -> service.apply(event("evt-sin-cupo", CONFIRMED, sinCupos.getId())))
                .isInstanceOf(NonRecoverableEventException.class)
                .hasMessageContaining("sin disponibilidad")
                // El contrato pide un ERROR claro con el código de la reserva.
                .hasMessageContaining("RSV-000002");

        // Lo que importa: la unidad sigue en 0, nunca en -1.
        assertThat(units.findById(sinCupos.getId()).orElseThrow().getAvailableStock()).isZero();
        // Y el evento NO queda marcado como procesado: si alguien arregla el stock y lo
        // reinyecta desde la DLQ, tiene que poder aplicarse.
        assertThat(processedEvents.findById("evt-sin-cupo")).isEmpty();
    }

    @Test
    @DisplayName("Cancelar una unidad que ya tiene todos sus cupos libres no se pasa de totalStock")
    void cancelledOnFullUnitDoesNotExceedTotalStock() {
        Unit full = units.save(new Unit("UN-EVT-003", "Lodge Torres Base", UnitType.LODGE,
                "AndesStay Lodge", "Puerto Natales, Magallanes", 6, new BigDecimal("189000"),
                2, 2, Boolean.TRUE));

        AvailabilityOutcome outcome =
                service.apply(event("evt-cancelado-sin-efecto", CANCELLED, full.getId()));

        // Es el caso normal de una reserva cancelada que nunca se confirmó: se confirma el
        // mensaje (no es un error) pero la disponibilidad no se infla.
        assertThat(outcome).isEqualTo(AvailabilityOutcome.RELEASE_SKIPPED);
        Unit reloaded = units.findById(full.getId()).orElseThrow();
        assertThat(reloaded.getAvailableStock()).isEqualTo(reloaded.getTotalStock());
        assertThat(processedEvents.findById("evt-cancelado-sin-efecto")).isPresent();
    }

    @Test
    @DisplayName("Una unidad que no existe en el catálogo también va a la DLQ")
    void unknownUnitIsNonRecoverable() {
        assertThatThrownBy(() -> service.apply(event("evt-unidad-fantasma", CONFIRMED, 999999L)))
                .isInstanceOf(NonRecoverableEventException.class)
                .hasMessageContaining("999999")
                .hasMessageContaining("RSV-000002");
    }

    @Test
    @DisplayName("Un type que no mueve stock va a la DLQ sin tocar la disponibilidad")
    void eventTypeThatDoesNotMoveStockIsNonRecoverable() {
        assertThatThrownBy(() ->
                service.apply(event("evt-checkin", "reservation.checkin", unitId)))
                .isInstanceOf(NonRecoverableEventException.class)
                .hasMessageContaining("reservation.checkin");

        assertThat(stock()).isEqualTo(3);
        assertThat(processedEvents.count()).isZero();
    }

    // ---------------------------------------------------------------------------------------
    //  Ayudas
    // ---------------------------------------------------------------------------------------

    private int stock() {
        return units.findById(unitId).orElseThrow().getAvailableStock();
    }

    /** Envelope del contrato de la EP3 con el payload completo del evento de reserva. */
    private ReservationEventEnvelope event(String eventId, String type, Long unitId) {
        ReservationEventPayload payload = new ReservationEventPayload("RSV-000002", unitId,
                "Cabana Rio Espolon", "ia.reyes@duocuc.cl", "Ian Reyes", "CONFIRMADA", "CREADA",
                LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 22), 2,
                new BigDecimal("236000"), Instant.parse("2026-10-17T20:15:30Z"));
        return new ReservationEventEnvelope(type, eventId,
                Instant.parse("2026-10-17T20:15:30Z"), "9c94d6ef", "RSV-000002",
                "max.hernandezg@duocuc.cl", payload);
    }
}
