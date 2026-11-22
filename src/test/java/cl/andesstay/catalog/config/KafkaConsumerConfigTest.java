package cl.andesstay.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.andesstay.catalog.messaging.InvalidEventException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;

/**
 * La política de errores del stream termina en la DLT de ESTE servicio, sin clúster de Kafka: el
 * {@link KafkaTemplate} con que publica el recuperador va mockeado y se captura el registro que
 * habría salido hacia el broker.
 *
 * <p>Se ejercita el {@link DefaultErrorHandler} que arma {@link KafkaConsumerConfig}, el mismo
 * bean que usa el contenedor, y no una copia del resolvedor de destino: así la prueba cubre
 * también la clasificación de errores (qué se reintenta y qué no) y el cableado con el
 * {@code DeadLetterPublishingRecoverer}.</p>
 *
 * <p>Por qué importa: antes el destino se armaba como {@code <tópico>.DLT}, una DLT compartida
 * por todos los grupos que leen {@code reservations.events}. El contrato ahora es una
 * por consumidor ({@code <tópico de origen>.<servicio>.DLT}) y el nombre sale del yml; si alguien
 * volviera a derivarlo del tópico de origen, estas pruebas lo notarían.</p>
 */
class KafkaConsumerConfigTest {

    private static final String SOURCE_TOPIC = "reservations.events";
    private static final String CATALOG_DLT = "reservations.events.catalog.DLT";
    private static final String RESERVATION_CODE = "RSV-000002";
    private static final byte[] BODY =
            "{\"type\":\"reservation.confirmed\"".getBytes(StandardCharsets.UTF_8);

    private final KafkaConsumerConfig config = new KafkaConsumerConfig();

    @Test
    @DisplayName("Evento mal formado: sin reintentos, a reservations.events.catalog.DLT y no a la compartida")
    void invalidEventGoesToThisServiceDlt() {
        KafkaTemplate<String, byte[]> template = templateThatAcceptsEverything();
        DefaultErrorHandler handler =
                config.reservationErrorHandler(template, properties(CATALOG_DLT, 3));

        boolean recovered = handler.handleOne(
                failure(new InvalidEventException("JSON inválido")), failedRecord(),
                mock(Consumer.class), mock(MessageListenerContainer.class));

        // true = el registro se dio por recuperado al PRIMER fallo: el contenedor confirma su
        // offset y la partición sigue con el siguiente.
        assertThat(recovered).isTrue();
        ProducerRecord<String, byte[]> sent = sentRecord(template);
        assertThat(sent.topic())
                .isEqualTo(CATALOG_DLT)
                .isNotEqualTo(SOURCE_TOPIC + ".DLT");
        // Partición nula: la elige el productor por la clave (el destino va con -1), así la DLT
        // no necesita tener tantas particiones como el tópico de origen.
        assertThat(sent.partition()).isNull();
        // El registro llega a la DLT idéntico al original, con la clave de la reserva.
        assertThat(sent.key()).isEqualTo(RESERVATION_CODE);
        assertThat(sent.value()).isEqualTo(BODY);
        // Y con las cabeceras que permiten saber de dónde vino y por qué se descartó.
        assertThat(header(sent, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(SOURCE_TOPIC);
        assertThat(header(sent, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(InvalidEventException.class.getName());
    }

    @Test
    @DisplayName("Error recuperable: se reintenta hasta max-attempts y recién ahí va a la DLT del catálogo")
    void recoverableErrorGoesToThisServiceDltWhenAttemptsAreExhausted() {
        KafkaTemplate<String, byte[]> template = templateThatAcceptsEverything();
        DefaultErrorHandler handler =
                config.reservationErrorHandler(template, properties(CATALOG_DLT, 3));
        Exception databaseDown =
                failure(new DataAccessResourceFailureException("la base no responde"));
        ConsumerRecord<String, byte[]> record = failedRecord();
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);

        // Intentos 1 y 2: el handler pide reintentar y todavía no publica nada.
        assertThat(handler.handleOne(databaseDown, record, consumer, container)).isFalse();
        assertThat(handler.handleOne(databaseDown, record, consumer, container)).isFalse();
        verify(template, never()).send(any(ProducerRecord.class));

        // Intento 3 = max-attempts: se agota y el registro sale a la DLT de este servicio.
        assertThat(handler.handleOne(databaseDown, record, consumer, container)).isTrue();
        assertThat(sentRecord(template).topic()).isEqualTo(CATALOG_DLT);
    }

    @Test
    @DisplayName("El nombre de la DLT sale de la configuración, no del tópico de origen")
    void deadLetterTopicComesFromConfiguration() {
        KafkaTemplate<String, byte[]> template = templateThatAcceptsEverything();
        // Lo que haría ANDESSTAY_KAFKA_DLT_TOPIC en un entorno que use otro nombre.
        DefaultErrorHandler handler =
                config.reservationErrorHandler(template, properties("catalogo.otro-nombre.DLT", 3));

        handler.handleOne(failure(new InvalidEventException("envelope incompleto")),
                failedRecord(), mock(Consumer.class), mock(MessageListenerContainer.class));

        assertThat(sentRecord(template).topic()).isEqualTo("catalogo.otro-nombre.DLT");
    }

    @Test
    @DisplayName("Sin nombre de DLT el servicio no arranca, en vez de trancar la partición al primer fallo")
    void blankDeadLetterTopicFailsAtStartup() {
        KafkaTemplate<String, byte[]> template = templateThatAcceptsEverything();

        assertThatThrownBy(() -> config.reservationErrorHandler(template, properties(" ", 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("andesstay.kafka.topics.dead-letter");
    }

    // ---------------------------------------------------------------------------------------

    /** Las propiedades del yml, con la DLT y los intentos que pida cada prueba. */
    private static AndesStayKafkaProperties properties(String deadLetter, int maxAttempts) {
        // Backoff de 1 ms: la prueba de reintentos no tiene por qué esperar segundos reales.
        return new AndesStayKafkaProperties(true, "catalog-consumer", "ms-andesstay-catalog", 3,
                maxAttempts, 1L, new AndesStayKafkaProperties.Topics(SOURCE_TOPIC, deadLetter));
    }

    /** El registro que falló: tercera partición del tópico de hechos, clave = código de reserva. */
    private static ConsumerRecord<String, byte[]> failedRecord() {
        return new ConsumerRecord<>(SOURCE_TOPIC, 2, 41L, RESERVATION_CODE, BODY);
    }

    /** Así le llega la excepción al handler: envuelta por el contenedor del listener. */
    private static Exception failure(Exception cause) {
        return new ListenerExecutionFailedException("falló el listener", cause);
    }

    /**
     * Template que confirma cada envío al instante, como si el broker hubiera respondido. El
     * recuperador espera esa confirmación: si el envío a la DLT fallara, el registro NO se daría
     * por recuperado.
     */
    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, byte[]> templateThatAcceptsEverything() {
        KafkaTemplate<String, byte[]> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, byte[]> out = invocation.getArgument(0);
            RecordMetadata metadata =
                    new RecordMetadata(new TopicPartition(out.topic(), 0), 0L, 0, 0L, 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(out, metadata));
        });
        return template;
    }

    /** El único registro que el recuperador mandó al broker. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ProducerRecord<String, byte[]> sentRecord(KafkaTemplate<String, byte[]> template) {
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, times(1)).send(captor.capture());
        return captor.getValue();
    }

    private static String header(ProducerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).as("cabecera %s", name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
