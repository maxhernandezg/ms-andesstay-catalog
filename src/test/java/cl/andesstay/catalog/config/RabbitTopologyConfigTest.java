package cl.andesstay.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Comprueba que la topología que declara el catálogo es EXACTAMENTE la del contrato de la EP3 y
 * que todos los nombres salen del application.yml (configuración centralizada).
 *
 * <p>Levanta el contexto de Spring pero NO toca el broker: en el perfil de pruebas
 * {@code andesstay.rabbit.enabled} está en false, así que el consumidor no arranca y nadie abre
 * una conexión AMQP.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class RabbitTopologyConfigTest {

    private static final String DLX_ARG = "x-dead-letter-exchange";
    private static final String DLRK_ARG = "x-dead-letter-routing-key";

    /** Nunca se llama: garantiza que ninguna prueba intente resolver el JWKS real de Azure. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private AndesStayRabbitProperties props;

    @Autowired
    private List<Queue> queues;

    @Autowired
    private List<Exchange> exchanges;

    @Autowired
    private List<Binding> bindings;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Test
    @DisplayName("El application.yml trae los nombres del contrato (nada hardcodeado en el código)")
    void propertiesCarryTheContract() {
        assertThat(props.maxAttempts()).isEqualTo(3);
        assertThat(props.attemptHeader()).isEqualTo("x-attempt");
        // En el perfil de pruebas la mensajería queda apagada: ninguna prueba toca la red.
        assertThat(props.enabled()).isFalse();

        assertThat(props.exchanges().eventsTopic()).isEqualTo("events.topic");
        assertThat(props.exchanges().eventsDlx()).isEqualTo("events.dead.dlx");
        assertThat(props.queues().catalog()).isEqualTo("q.evt.catalog");
        assertThat(props.queues().catalogDlq()).isEqualTo("q.evt.catalog.dlq");
        assertThat(props.routingKeys().reservationConfirmed()).isEqualTo("reservation.confirmed");
        assertThat(props.routingKeys().reservationCancelled()).isEqualTo("reservation.cancelled");
        assertThat(props.routingKeys().catalogDead()).isEqualTo("catalog.dead");

        assertThat(props.routingKeys().boundEvents())
                .containsExactly("reservation.confirmed", "reservation.cancelled");
    }

    @Test
    @DisplayName("Declara events.topic, events.dead.dlx y q.evt.catalog con su dead-letter")
    void declaresItsPartOfTheTopology() {
        assertThat(exchanges).hasSize(2);
        Exchange eventsTopic = exchangeNamed("events.topic");
        assertThat(eventsTopic.getType()).isEqualTo(ExchangeTypes.TOPIC);
        assertThat(eventsTopic.isDurable()).isTrue();

        Exchange eventsDlx = exchangeNamed("events.dead.dlx");
        assertThat(eventsDlx.getType()).isEqualTo(ExchangeTypes.DIRECT);
        assertThat(eventsDlx.isDurable()).isTrue();

        assertThat(queues).hasSize(2);
        Queue catalog = queueNamed("q.evt.catalog");
        assertThat(catalog.isDurable()).isTrue();
        Map<String, Object> args = catalog.getArguments();
        assertThat(args).containsEntry(DLX_ARG, "events.dead.dlx")
                .containsEntry(DLRK_ARG, "catalog.dead");

        Queue dlq = queueNamed("q.evt.catalog.dlq");
        assertThat(dlq.isDurable()).isTrue();
        // La DLQ NO lleva dead-letter propio: si lo tuviera, un mensaje podría rebotar en
        // círculos entre colas de descarte.
        assertThat(dlq.getArguments()).doesNotContainKey(DLX_ARG);
    }

    @Test
    @DisplayName("q.evt.catalog se ata a las dos claves exactas, NO al patrón reservation.#")
    void bindsOnlyTheTwoEventsThatMoveStock() {
        assertThat(bindings).hasSize(3);

        assertThat(bindings).anySatisfy(binding -> {
            assertThat(binding.getDestination()).isEqualTo("q.evt.catalog");
            assertThat(binding.getExchange()).isEqualTo("events.topic");
            assertThat(binding.getRoutingKey()).isEqualTo("reservation.confirmed");
        });
        assertThat(bindings).anySatisfy(binding -> {
            assertThat(binding.getDestination()).isEqualTo("q.evt.catalog");
            assertThat(binding.getExchange()).isEqualTo("events.topic");
            assertThat(binding.getRoutingKey()).isEqualTo("reservation.cancelled");
        });
        assertThat(bindings).anySatisfy(binding -> {
            assertThat(binding.getDestination()).isEqualTo("q.evt.catalog.dlq");
            assertThat(binding.getExchange()).isEqualTo("events.dead.dlx");
            assertThat(binding.getRoutingKey()).isEqualTo("catalog.dead");
        });

        // Con el comodín llegarían también created, checkin y checkout, que no mueven stock.
        assertThat(bindings).extracting(Binding::getRoutingKey).doesNotContain("reservation.#");
    }

    @Test
    @DisplayName("NO declara colas de otros servicios (q.evt.report, q.evt.audit ni las de comandos)")
    void doesNotDeclareOtherServicesQueues() {
        // Cada servicio declara solo lo suyo: q.evt.report es de ms-andesstay-report, q.evt.audit
        // de ms-andesstay-audit y las colas q.cmd.* de ms-andesstay-notify. Declararlas acá sería
        // invadir otro servicio y, peor, podría crearlas con argumentos distintos a los que
        // espera su dueño (RabbitMQ responde PRECONDITION_FAILED - inequivalent arg).
        assertThat(queues.stream().map(Queue::getName))
                .containsExactlyInAnyOrder("q.evt.catalog", "q.evt.catalog.dlq");
        assertThat(exchanges.stream().map(Exchange::getName))
                .doesNotContain("cmd.direct", "cmd.topic", "cmd.dead.dlx");
    }

    @Test
    @DisplayName("El consumidor escucha q.evt.catalog con ACK MANUAL (y apagado en pruebas)")
    void listenerIsBoundToTheContractQueueWithManualAck() {
        assertThat(listenerRegistry.getListenerContainers()).hasSize(1);
        AbstractMessageListenerContainer container = (AbstractMessageListenerContainer)
                listenerRegistry.getListenerContainers().iterator().next();

        // La cola sale del yml por placeholder, no de un literal en la clase del consumidor.
        assertThat(container.getQueueNames()).containsExactly("q.evt.catalog");
        // El indicador más pesado de la EP3: el ACK lo decide el consumidor, mensaje a mensaje.
        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        // andesstay.rabbit.enabled=false en el perfil de pruebas: el contenedor existe pero no
        // arranca, así que ninguna prueba abre una conexión al broker.
        assertThat(container.isAutoStartup()).isFalse();
        assertThat(container.isRunning()).isFalse();
    }

    private Queue queueNamed(String name) {
        return queues.stream().filter(queue -> name.equals(queue.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("No se declaró la cola " + name));
    }

    private Exchange exchangeNamed(String name) {
        return exchanges.stream().filter(exchange -> name.equals(exchange.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("No se declaró el exchange " + name));
    }
}
