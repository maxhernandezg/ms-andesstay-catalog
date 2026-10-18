package cl.andesstay.catalog.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declaración de la parte del contrato de mensajería de la EP3 que le toca al catálogo.
 *
 * <p>Esta clase es <strong>solo configuración</strong>: no tiene lógica de negocio y nadie la
 * llama. Spring descubre los beans {@link Queue}, {@link org.springframework.amqp.core.Exchange}
 * y {@link Binding}, y el {@code RabbitAdmin} que autoconfigura Spring Boot los declara en el
 * broker en cuanto se abre la primera conexión (es decir, cuando arranca el consumidor). Así la
 * cola existe sin que el servicio de dominio tenga que crearla a mano, y el servicio puede
 * arrancar antes que el broker sin romperse.</p>
 *
 * <p>Ningún nombre está escrito acá: todos salen de {@link AndesStayRabbitProperties}, o sea del
 * {@code application.yml}.</p>
 *
 * <h2>Ruta del evento, de punta a punta</h2>
 * <pre>
 *  ms-andesstay-reservations
 *        │  publica reservation.created | .confirmed | .checkin | .checkout | .cancelled
 *        ▼
 *   events.topic (topic, durable)
 *        ├── 'reservation.#' ─────────────▶ q.evt.report    (KPIs)
 *        ├── 'reservation.#' ─────────────▶ q.evt.audit     (bitácora)
 *        └── 'reservation.confirmed' ────▶ q.evt.catalog   (esta cola: disponibilidad)
 *            'reservation.cancelled'  ───┘
 *                                   │
 *        q.evt.catalog, al hacer basicNack(requeue=false), dead-letterea por
 *        x-dead-letter-exchange=events.dead.dlx con x-dead-letter-routing-key=catalog.dead
 *                                   ▼
 *                        events.dead.dlx (direct, durable)
 *                                   │ 'catalog.dead'
 *                                   ▼
 *                          q.evt.catalog.dlq
 * </pre>
 *
 * <p>El mismo {@code reservation.confirmed} llega a reportería, a auditoría y al catálogo: ese es
 * el pub/sub real que pide la evaluación, y es lo que permite que la disponibilidad baje
 * <strong>sin que ms-andesstay-reservations llame al catálogo</strong>. El productor no sabe que
 * esta cola existe.</p>
 *
 * <p>Dos bindings exactos en vez de {@code reservation.#}: al catálogo solo le incumben los dos
 * eventos que mueven stock. Con el comodín recibiría también {@code created}, {@code checkin} y
 * {@code checkout}, que no cambian la disponibilidad, y el consumidor tendría que descartarlos a
 * mano (o mandarlos a la DLQ) en cada entrega.</p>
 */
@Configuration
public class RabbitTopologyConfig {

    private final AndesStayRabbitProperties props;

    public RabbitTopologyConfig(AndesStayRabbitProperties props) {
        this.props = props;
    }

    // =====================================================================================
    //  Exchanges (durables: sobreviven al reinicio del broker)
    // =====================================================================================

    /**
     * PUB/SUB de eventos de dominio. Es topic porque el MISMO evento
     * ({@code reservation.confirmed}) tiene que llegar a reportería, a auditoría y al catálogo.
     */
    @Bean
    public TopicExchange eventsTopicExchange() {
        return ExchangeBuilder.topicExchange(props.exchanges().eventsTopic()).durable(true).build();
    }

    /** Dead-letter exchange de las colas de eventos. Es direct: cada DLQ tiene su routing key. */
    @Bean
    public DirectExchange eventsDeadLetterExchange() {
        return ExchangeBuilder.directExchange(props.exchanges().eventsDlx()).durable(true).build();
    }

    // =====================================================================================
    //  Colas
    // =====================================================================================

    /**
     * Cola del catálogo. Los argumentos de dead-letter son los que hacen que un
     * {@code basicNack(deliveryTag, false, false)} termine en {@code q.evt.catalog.dlq}: el
     * broker se encarga del descarte, el consumidor no republica nada a mano.
     */
    @Bean
    public Queue catalogEventQueue() {
        return QueueBuilder.durable(props.queues().catalog())
                .deadLetterExchange(props.exchanges().eventsDlx())
                .deadLetterRoutingKey(props.routingKeys().catalogDead())
                .build();
    }

    /**
     * Cola de descarte. NO lleva dead-letter propio: si una DLQ tuviera DLX, un mensaje podría
     * rebotar en círculos entre colas de descarte.
     */
    @Bean
    public Queue catalogDeadLetterQueue() {
        return QueueBuilder.durable(props.queues().catalogDlq()).build();
    }

    // =====================================================================================
    //  Bindings
    // =====================================================================================

    /** La confirmación de una reserva: es la que descuenta un cupo. */
    @Bean
    public Binding catalogConfirmedBinding() {
        return BindingBuilder.bind(catalogEventQueue()).to(eventsTopicExchange())
                .with(props.routingKeys().reservationConfirmed());
    }

    /** La cancelación: devuelve el cupo. */
    @Bean
    public Binding catalogCancelledBinding() {
        return BindingBuilder.bind(catalogEventQueue()).to(eventsTopicExchange())
                .with(props.routingKeys().reservationCancelled());
    }

    /** El DLX reparte por la routing key de descarte del catálogo. */
    @Bean
    public Binding catalogDeadLetterBinding() {
        return BindingBuilder.bind(catalogDeadLetterQueue()).to(eventsDeadLetterExchange())
                .with(props.routingKeys().catalogDead());
    }
}
