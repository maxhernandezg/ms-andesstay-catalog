package cl.andesstay.catalog.config;

import cl.andesstay.catalog.messaging.InvalidEventException;
import cl.andesstay.catalog.messaging.NonRecoverableEventException;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Cliente consumidor de Kafka: la fábrica de consumidores, el contenedor de listeners y la
 * política de errores con dead letter topic.
 *
 * <p>Es el equivalente de {@code RabbitListenerConfig} para el stream: <strong>infraestructura de
 * mensajería, sin una línea de lógica de negocio</strong>. Qué hacer con un evento lo decide
 * {@code UnitAvailabilityService}; acá solo se define cómo se lee, cuántas veces se
 * reintenta y dónde termina lo que no se pudo procesar.</p>
 *
 * <h2>1. Por qué el valor se lee como {@code byte[]} y no como un DTO</h2>
 * <p>Spring Kafka puede deserializar el JSON directo a {@code ReservationEventEnvelope} con un
 * {@code JsonDeserializer}. Acá se lee crudo a propósito, por la misma razón por la que el
 * consumidor de RabbitMQ no usa un {@code MessageConverter}: con el deserializador automático un
 * JSON malformado explota <strong>dentro del poll</strong>, antes de llegar al método, y el
 * mensaje de error que queda en el log no trae ni el {@code eventId} ni el {@code traceId}.
 * Leyendo el cuerpo crudo, el mismo {@code ReservationEventParser} que valida los mensajes de
 * AMQP valida los del stream: <strong>una sola definición de "este evento es basura"</strong>
 * para los dos transportes, y un solo lugar que probar.</p>
 *
 * <h2>2. La política de errores, que es la misma del contrato</h2>
 * <table>
 *   <caption>Qué pasa con un registro que falla</caption>
 *   <tr><th>Situación</th><th>Qué hace el contenedor</th></tr>
 *   <tr><td>Error NO recuperable ({@link InvalidEventException}: JSON inválido, envelope
 *       incompleto, estado desconocido)</td>
 *       <td><strong>cero reintentos</strong> y derecho a la DLT: reintentar un mensaje mal
 *       formado da exactamente el mismo resultado tres veces</td></tr>
 *   <tr><td>Error RECUPERABLE (base de datos caída, timeout, bloqueo)</td>
 *       <td>hasta {@code andesstay.kafka.max-attempts} intentos con espera entre medio; si se
 *       agotan, a la DLT</td></tr>
 *   <tr><td>Éxito</td><td>se confirma el offset de ese registro</td></tr>
 * </table>
 *
 * <p><strong>Por qué la DLT no es opcional.</strong> En RabbitMQ un mensaje que falla se puede
 * rechazar y la cola sigue entregando los siguientes. En Kafka no: los registros de una
 * partición se leen en orden y el offset avanza de a uno, así que un evento envenenado que se
 * reintenta para siempre <em>bloquea su partición completa</em> y ningún evento posterior de esa
 * reserva se procesa jamás. El {@link DeadLetterPublishingRecoverer} lo saca del camino
 * publicándolo en {@code <tópico>.DLT} con las cabeceras del error, y el consumidor sigue.</p>
 *
 * <h2>3. Por qué el offset se confirma registro a registro</h2>
 * <p>{@link AckMode#RECORD} confirma el offset después de cada registro procesado con éxito. El
 * default ({@code BATCH}) confirma el lote completo al final del poll: si el servicio se cae en
 * medio del lote, al volver se reprocesan todos los registros del lote. No sería incorrecto
 * —cada consumidor es idempotente por {@code eventId}— pero sí trabajo repetido y ruido en el
 * log, y la idempotencia es una red de seguridad, no una excusa para reprocesar de más.</p>
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /**
     * Consumidores del tópico de hechos. La clave se lee como texto (es el
     * {@code reservationCode}) y el valor como bytes (ver el punto 1 del javadoc).
     */
    @Bean
    public ConsumerFactory<String, byte[]> reservationConsumerFactory(
            KafkaProperties kafkaProperties, AndesStayKafkaProperties props) {

        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, props.consumerGroup());
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, props.clientId());
        // Un grupo nuevo empieza por el principio del tópico y no por el final: así reportería
        // puede reconstruir su tabla de hechos releyendo los 7 días que el tópico conserva, que
        // es justo lo que una cola de RabbitMQ no permite hacer.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // El contenedor confirma los offsets (ver punto 3): el commit automático del cliente
        // confirmaría cada cinco segundos aunque el registro todavía no se haya procesado.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * Productor que usa el recuperador para escribir en la DLT.
     *
     * <p>Publica el cuerpo <strong>tal cual llegó</strong> ({@code byte[]}): un evento que se
     * descarta tiene que quedar en la DLT idéntico al original, incluso si el motivo del
     * descarte fue que su JSON no se podía leer.</p>
     */
    @Bean
    public KafkaTemplate<String, byte[]> deadLetterKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        ProducerFactory<String, byte[]> factory = new DefaultKafkaProducerFactory<>(config);
        return new KafkaTemplate<>(factory);
    }

    /**
     * Política de errores del contrato: reintentos para lo recuperable, DLT para todo lo que no
     * se pudo procesar.
     */
    @Bean
    public DefaultErrorHandler reservationErrorHandler(
            KafkaTemplate<String, byte[]> deadLetterKafkaTemplate,
            AndesStayKafkaProperties props) {

        // El destino de la DLT va EXPLICITO a proposito. El resolvedor por defecto de
        // Spring Kafka publica en "<topico>-dlt" (guion, minusculas), y los topicos del
        // contrato -los que crea infra/kafka/topics.sh- son "<topico>.DLT". Con la
        // auto-creacion de topicos apagada, el recoverer no encontraba su destino,
        // respondia UNKNOWN_TOPIC_OR_PARTITION en bucle y el registro nunca se recuperaba:
        // UN solo mensaje mal formado dejaba la particion trancada para siempre.
        //
        // La particion destino va en -1 a proposito: la elige el productor por la clave.
        // Fijarla a la de origen obligaria a que la DLT tenga siempre al menos tantas
        // particiones como el topico original.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (registro, excepcion) ->
                        new TopicPartition(registro.topic() + ".DLT", -1));

        // max-attempts son los intentos TOTALES, así que los reintentos son uno menos.
        // Backoff EXPONENCIAL y no fijo: si mil registros fallan por la misma base de datos
        // caída, reintentarlos todos cada segundo es un DDoS contra el propio sistema. Subiendo
        // la espera (1s, 2s, 4s…) se le da tiempo real al servicio externo a recuperarse.
        // max-attempts son los intentos TOTALES, así que los reintentos son uno menos.
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(Math.max(0, props.maxAttempts() - 1));
        backOff.setInitialInterval(props.retryBackoffMs());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(props.retryBackoffMs() * 8);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Un evento mal formado no mejora porque se reintente: se va derecho a la DLT. Lo mismo
        // vale para un dato de negocio imposible (una unidad que no existe en el catálogo, o una
        // confirmación sin disponibilidad): reintentarlo tres veces da el mismo resultado y solo
        // retrasa los eventos que vienen detrás en la misma partición.
        handler.addNotRetryableExceptions(InvalidEventException.class,
                NonRecoverableEventException.class);
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.ERROR);
        handler.setRetryListeners((record, ex, intento) ->
                log.warn("Reintento {} del registro {}-{} offset {} (reserva={}): {}", intento,
                        record.topic(), record.partition(), record.offset(), record.key(),
                        ex.toString()));
        return handler;
    }

    /** Contenedor de los {@code @KafkaListener} de este servicio. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]>
            reservationKafkaListenerContainerFactory(
                    ConsumerFactory<String, byte[]> reservationConsumerFactory,
                    DefaultErrorHandler reservationErrorHandler,
                    AndesStayKafkaProperties props) {

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(reservationConsumerFactory);
        factory.setCommonErrorHandler(reservationErrorHandler);
        // Un hilo por partición: ver el javadoc de AndesStayKafkaProperties.concurrency().
        factory.setConcurrency(props.concurrency());
        factory.getContainerProperties().setAckMode(AckMode.RECORD);
        return factory;
    }
}
