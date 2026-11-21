package cl.andesstay.catalog.messaging;

import cl.andesstay.catalog.config.AndesStayRabbitProperties;
import cl.andesstay.catalog.service.AvailabilityOutcome;
import cl.andesstay.catalog.service.UnitAvailabilityService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor del stream: mueve la disponibilidad de la unidad con los eventos de
 * {@code reservations.events}, en el grupo {@code catalog-consumer}.
 *
 * <p>Es el gemelo de {@link AvailabilityEventListener}, que hace lo mismo con la cola
 * {@code q.evt.catalog} de RabbitMQ, y usa el mismo parser y el mismo
 * {@link UnitAvailabilityService}.</p>
 *
 * <h2>La diferencia que obliga a filtrar acá</h2>
 * <p>En RabbitMQ el <strong>broker</strong> filtra: {@code q.evt.catalog} está atada a
 * {@code events.topic} solo con las routing keys {@code reservation.confirmed} y
 * {@code reservation.cancelled}, así que a la cola nunca llega un {@code created} ni un
 * {@code checkout}. Un tópico de Kafka <strong>no tiene routing keys</strong>: el grupo recibe
 * todos los registros del tópico y el filtro pasa a ser responsabilidad del consumidor.</p>
 *
 * <p>Por eso este listener descarta explícitamente lo que no mueve el calendario de la unidad.
 * No es una molestia, es el intercambio del modelo: el broker de colas enruta y entrega menos
 * mensajes, el log de eventos entrega todo y deja que cada consumidor decida. A cambio, sumar un
 * consumidor nuevo a un tópico no obliga a declarar ningún binding, y un evento que mañana
 * también libere la unidad ({@code reservation.noshow}) se agrega acá sin tocar al productor ni
 * la topología.</p>
 *
 * <p><strong>Descartar no es fallar.</strong> Un {@code created} que se ignora retorna normal y
 * el offset avanza. Lo que sí es un error no recuperable —y por eso lo lanza
 * {@link UnitAvailabilityService}— es que llegue un tipo inesperado por la cola de AMQP, donde
 * el binding garantizaba que eso no podía pasar.</p>
 */
@Component
public class AvailabilityEventStreamListener {

    private static final Logger log =
            LoggerFactory.getLogger(AvailabilityEventStreamListener.class);

    private final ReservationEventParser parser;
    private final UnitAvailabilityService availabilityService;
    private final String confirmed;
    private final String cancelled;

    public AvailabilityEventStreamListener(ReservationEventParser parser,
                                           UnitAvailabilityService availabilityService,
                                           AndesStayRabbitProperties props) {
        this.parser = parser;
        this.availabilityService = availabilityService;
        // Los nombres salen del application.yml igual que en el camino de AMQP: nada escrito a
        // mano. El nombre del evento es del contrato, no del transporte.
        this.confirmed = props.routingKeys().reservationConfirmed();
        this.cancelled = props.routingKeys().reservationCancelled();
    }

    /**
     * Procesa un evento del stream.
     *
     * @param record registro crudo: se necesitan la partición y el offset para el log
     */
    @KafkaListener(
            topics = "${andesstay.kafka.topics.reservation-events}",
            groupId = "${andesstay.kafka.consumer-group}",
            containerFactory = "reservationKafkaListenerContainerFactory",
            autoStartup = "${andesstay.kafka.enabled:true}")
    public void onReservationEvent(ConsumerRecord<String, byte[]> record) {
        ReservationEventEnvelope event = parser.parse(record.value());

        String type = event.type();
        if (!confirmed.equals(type) && !cancelled.equals(type)) {
            log.debug("Evento {} ignorado: no mueve la disponibilidad · reserva={} {}-{} "
                            + "offset={}", type, event.payload().reservationCode(),
                    record.topic(), record.partition(), record.offset());
            return;
        }

        AvailabilityOutcome outcome = availabilityService.apply(event);
        if (outcome == AvailabilityOutcome.DUPLICATE) {
            log.info("Evento repetido, no se vuelve a mover el stock: type={} eventId={} "
                            + "traceId={} reserva={} {}-{} offset={}", type, event.eventId(),
                    event.traceId(), event.payload().reservationCode(), record.topic(),
                    record.partition(), record.offset());
            return;
        }
        log.info("Disponibilidad actualizada ({}): type={} eventId={} traceId={} reserva={} "
                        + "unidad={} {}-{} offset={}", outcome, type, event.eventId(),
                event.traceId(), event.payload().reservationCode(), event.payload().unitId(),
                record.topic(), record.partition(), record.offset());
    }
}
