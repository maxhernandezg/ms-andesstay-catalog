package cl.andesstay.catalog.messaging;

import cl.andesstay.catalog.config.AndesStayRabbitProperties;
import cl.andesstay.catalog.service.AvailabilityOutcome;
import cl.andesstay.catalog.service.UnitAvailabilityService;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumidor de los eventos de dominio de reserva que mueven la disponibilidad del catálogo.
 *
 * <p>Escucha {@code q.evt.catalog}, que está bindeada a {@code events.topic} con las dos claves
 * exactas {@code reservation.confirmed} y {@code reservation.cancelled}. El MISMO evento llega
 * también a {@code q.evt.report} y a {@code q.evt.audit} (pub/sub real): los tres consumidores
 * son independientes y lo que haga uno no afecta al otro.</p>
 *
 * <p>Esta clase <strong>solo coordina</strong>: parsea con {@link ReservationEventParser},
 * delega el negocio en {@link UnitAvailabilityService} y decide qué hacer con el mensaje. No
 * tiene ni una regla de stock adentro.</p>
 *
 * <h2>Política de ACK y errores (contrato de la EP3)</h2>
 * <table>
 *   <tr><th>Situación</th><th>Qué hace</th><th>Log</th></tr>
 *   <tr><td>Éxito (descontó o liberó)</td><td>{@code basicAck(deliveryTag, false)}</td>
 *       <td>INFO</td></tr>
 *   <tr><td>Reenvío del mismo {@code eventId}</td>
 *       <td>{@code basicAck} sin repetir el efecto</td><td>INFO</td></tr>
 *   <tr><td>Error NO recuperable (JSON inválido, envelope incompleto, unidad inexistente, sin
 *       disponibilidad)</td>
 *       <td>{@code basicNack(deliveryTag, false, false)} → el broker lo manda al DLX</td>
 *       <td>ERROR con eventId, traceId y motivo</td></tr>
 *   <tr><td>Error RECUPERABLE (base caída, timeout) con {@code x-attempt < max-attempts}</td>
 *       <td>republica el mensaje con {@code x-attempt + 1} y hace {@code basicAck} del original</td>
 *       <td>WARN</td></tr>
 *   <tr><td>Error RECUPERABLE con los intentos agotados (o si no se puede republicar)</td>
 *       <td>{@code basicNack(deliveryTag, false, false)} → DLQ</td>
 *       <td>ERROR</td></tr>
 * </table>
 *
 * <p><strong>Nunca</strong>, en ningún camino, se hace {@code requeue=true}: eso devuelve el
 * mensaje al frente de la cola SIN aumentar el contador y, con un error que no se arregla solo,
 * produce un bucle infinito que deja la cola girando sobre el mismo mensaje (poison message). El
 * reintento es acotado y explícito: se republica una copia con el contador {@code x-attempt} en el
 * header y, al pasarse del máximo, el mensaje se descarta a la DLQ para revisarlo a mano.</p>
 *
 * <p>El ACK es MANUAL porque así lo pide el contrato; se configura en el {@code application.yml}
 * ({@code spring.rabbitmq.listener.simple.acknowledge-mode: manual}), no en el código.</p>
 */
@Component
public class AvailabilityEventListener {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityEventListener.class);

    /**
     * Exchange por defecto de AMQP (el de nombre vacío): enruta por nombre de cola. Se usa para
     * republicar el reintento SOLO en {@code q.evt.catalog}. Republicar en {@code events.topic}
     * sería un error: el evento volvería a llegarles también a reportería y a auditoría, que ya
     * lo procesaron bien, y les duplicaría el hecho.
     */
    private static final String DEFAULT_EXCHANGE = "";

    private final ReservationEventParser parser;
    private final UnitAvailabilityService availabilityService;
    private final RabbitTemplate rabbitTemplate;
    private final AndesStayRabbitProperties props;

    public AvailabilityEventListener(ReservationEventParser parser,
                                     UnitAvailabilityService availabilityService,
                                     RabbitTemplate rabbitTemplate,
                                     AndesStayRabbitProperties props) {
        this.parser = parser;
        this.availabilityService = availabilityService;
        this.rabbitTemplate = rabbitTemplate;
        this.props = props;
    }

    /**
     * Procesa un evento de reserva y mueve la disponibilidad de la unidad.
     *
     * <p>El nombre de la cola NO está escrito acá: sale del {@code application.yml} por
     * placeholder ({@code andesstay.rabbit.queues.catalog}), igual que {@code autoStartup}, que
     * permite arrancar el servicio sin mensajería cuando no hay broker (el CRUD del catálogo
     * sigue funcionando igual).</p>
     *
     * @param message mensaje crudo: se necesita el {@code deliveryTag} y el header
     *                {@code x-attempt}, así que no se usa conversión automática del cuerpo
     * @param channel canal AMQP de la entrega, para el ACK/NACK manual
     */
    @RabbitListener(
            queues = "${andesstay.rabbit.queues.catalog}",
            autoStartup = "${andesstay.rabbit.enabled:true}")
    public void onReservationEvent(Message message, Channel channel) throws IOException {
        MessageProperties amqp = message.getMessageProperties();
        long deliveryTag = amqp.getDeliveryTag();
        int attempt = attemptOf(amqp);

        ReservationEventEnvelope event;
        try {
            event = parser.parse(message.getBody());
        } catch (NonRecoverableEventException ex) {
            // NO recuperable: reintentarlo daría lo mismo. Se descarta derecho a la DLQ.
            log.error("Evento descartado a la DLQ (error no recuperable): {} · messageId={} "
                            + "routingKey={} intento={}", ex.getMessage(), amqp.getMessageId(),
                    amqp.getReceivedRoutingKey(), attempt, ex);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        try {
            AvailabilityOutcome outcome = availabilityService.apply(event);
            if (outcome == AvailabilityOutcome.DUPLICATE) {
                // Idempotencia: RabbitMQ entrega "al menos una vez", así que el reenvío es normal.
                log.info("Evento repetido, la disponibilidad no se vuelve a mover: type={} "
                                + "eventId={} traceId={} reserva={} intento={}", event.type(),
                        event.eventId(), event.traceId(), event.payload().reservationCode(),
                        attempt);
            } else {
                log.info("Evento procesado ({}): type={} eventId={} traceId={} reserva={} "
                                + "unidad={} intento={}", outcome, event.type(), event.eventId(),
                        event.traceId(), event.payload().reservationCode(),
                        event.payload().unitId(), attempt);
            }
            channel.basicAck(deliveryTag, false);
        } catch (NonRecoverableEventException ex) {
            // Dato de negocio imposible (sin disponibilidad, unidad inexistente, tipo que no mueve
            // stock). El mensaje está bien formado, pero no se puede aplicar ni ahora ni nunca.
            log.error("Evento descartado a la DLQ (error no recuperable): {} · eventId={} "
                            + "traceId={} reserva={}", ex.getMessage(), event.eventId(),
                    event.traceId(), event.payload().reservationCode(), ex);
            channel.basicNack(deliveryTag, false, false);
        } catch (RuntimeException ex) {
            // RECUPERABLE: base de datos caída, timeout, choque de concurrencia. El dato es bueno,
            // el momento no.
            handleRecoverable(message, channel, event, attempt, ex);
        }
    }

    /** Reintento acotado y, al agotarse, descarte a la DLQ. */
    private void handleRecoverable(Message message, Channel channel,
                                   ReservationEventEnvelope event, int attempt, RuntimeException ex)
            throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int maxAttempts = props.maxAttempts();

        if (attempt >= maxAttempts) {
            log.error("Evento a la DLQ tras agotar los {} intentos: eventId={} traceId={} "
                            + "reserva={} causa={}", maxAttempts, event.eventId(), event.traceId(),
                    event.payload().reservationCode(), ex.toString(), ex);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        int nextAttempt = attempt + 1;
        try {
            rabbitTemplate.send(DEFAULT_EXCHANGE, props.queues().catalog(),
                    withAttempt(message, nextAttempt));
        } catch (RuntimeException publishError) {
            // Si tampoco se puede republicar, el mensaje se va a la DLQ igual que cuando se agotan
            // los intentos. NO se usa requeue=true ni acá: devolverlo a la cola sin aumentar el
            // contador lo deja volviendo a la cabeza con el MISMO x-attempt, así que si la
            // publicación sigue fallando (broker en flow control, conexión de publicación caída
            // mientras la del consumidor vive) el consumidor gira sobre el mismo mensaje sin
            // límite: es justo el requeue sin contador que el contrato prohíbe. En q.evt.catalog.dlq
            // el evento queda guardado, con su eventId y su traceId, y se puede reinyectar a mano
            // (la bitácora de idempotencia garantiza que no se aplique dos veces).
            log.error("No se pudo republicar el reintento {} de eventId={} traceId={} reserva={}: "
                            + "{}. El evento se manda a la DLQ para reinyectarlo a mano",
                    nextAttempt, event.eventId(), event.traceId(),
                    event.payload().reservationCode(), publishError.getMessage(), publishError);
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        log.warn("Error recuperable procesando eventId={} traceId={} reserva={} (intento {} de "
                        + "{}): {}. Republicado con {}={}", event.eventId(), event.traceId(),
                event.payload().reservationCode(), attempt, maxAttempts, ex.toString(),
                props.attemptHeader(), nextAttempt);
        // El original se confirma: ya existe una copia con el contador aumentado, dejarlo sin
        // confirmar duplicaría el trabajo cuando el broker lo reentregue.
        channel.basicAck(deliveryTag, false);
    }

    /**
     * Copia del mensaje con el contador de intentos aumentado. Se conservan las propiedades del
     * contrato ({@code messageId} = eventId, {@code contentType} application/json, entrega
     * PERSISTENT) y el resto de los headers, para que el reintento sea el mismo mensaje y no uno
     * nuevo.
     */
    private Message withAttempt(Message message, int nextAttempt) {
        MessageProperties original = message.getMessageProperties();
        MessageBuilderSupport<Message> builder = MessageBuilder.withBody(message.getBody())
                .setMessageId(original.getMessageId())
                .setContentType(original.getContentType() == null
                        ? MessageProperties.CONTENT_TYPE_JSON : original.getContentType())
                .setContentEncoding(original.getContentEncoding())
                .setCorrelationId(original.getCorrelationId())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        for (Map.Entry<String, Object> header : original.getHeaders().entrySet()) {
            builder.setHeader(header.getKey(), header.getValue());
        }
        return builder.setHeader(props.attemptHeader(), nextAttempt).build();
    }

    /**
     * Número de intento del header {@code x-attempt}. Si no viene (primera entrega, o un
     * publicador que no lo puso) se asume 1, que es lo que dice el contrato.
     */
    private int attemptOf(MessageProperties amqp) {
        Object header = amqp.getHeader(props.attemptHeader());
        if (header instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (header instanceof String text) {
            try {
                return Math.max(1, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                log.warn("Header {} con valor no numérico '{}': se asume el intento 1",
                        props.attemptHeader(), text);
            }
        }
        return 1;
    }
}
