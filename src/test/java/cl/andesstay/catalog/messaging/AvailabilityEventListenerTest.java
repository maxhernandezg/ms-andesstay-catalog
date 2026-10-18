package cl.andesstay.catalog.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cl.andesstay.catalog.config.AndesStayRabbitProperties;
import cl.andesstay.catalog.service.AvailabilityOutcome;
import cl.andesstay.catalog.service.UnitAvailabilityService;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Política de ACK y errores del consumidor, SIN broker: el {@link Channel} de AMQP va mockeado,
 * igual que el servicio de dominio, así cada caso del contrato se puede provocar a voluntad.
 *
 * <p>Casos cubiertos (son los que fija la EP3):</p>
 * <ol>
 *   <li>evento válido → se delega al dominio y {@code basicAck};</li>
 *   <li>JSON inválido / envelope incompleto → {@code basicNack} sin requeue (el broker lo manda a
 *       la DLQ), sin reintentos y con log ERROR;</li>
 *   <li>sin disponibilidad → {@code basicNack} sin requeue y log ERROR con el código de la
 *       reserva: es un problema de negocio que alguien tiene que mirar;</li>
 *   <li>error recuperable → se republica con {@code x-attempt + 1} y, al agotar los intentos (o
 *       si el broker no acepta la republicación), {@code basicNack} hacia la DLQ: en NINGÚN camino
 *       se usa {@code requeue=true};</li>
 *   <li>evento repetido → {@code basicAck} sin repetir el efecto.</li>
 * </ol>
 */
class AvailabilityEventListenerTest {

    private static final long DELIVERY_TAG = 42L;
    private static final String EVENT_ID = "6f1c0a42-9d3b-4f51-8f0e-2b7a1c9d4e55";
    private static final String QUEUE = "q.evt.catalog";
    private static final String ATTEMPT_HEADER = "x-attempt";

    private UnitAvailabilityService availabilityService;
    private RabbitTemplate rabbitTemplate;
    private Channel channel;
    private AvailabilityEventListener listener;
    private ListAppender<ILoggingEvent> logs;
    private Logger listenerLogger;

    /** Contrato tal como lo trae el application.yml, pero armado a mano (nada de red). */
    private static AndesStayRabbitProperties contractProperties() {
        return new AndesStayRabbitProperties(true, 3, ATTEMPT_HEADER,
                new AndesStayRabbitProperties.Exchanges("events.topic", "events.dead.dlx"),
                new AndesStayRabbitProperties.Queues(QUEUE, "q.evt.catalog.dlq"),
                new AndesStayRabbitProperties.RoutingKeys("reservation.confirmed",
                        "reservation.cancelled", "catalog.dead"));
    }

    @BeforeEach
    void setUp() {
        availabilityService = mock(UnitAvailabilityService.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        channel = mock(Channel.class);
        // El mismo ObjectMapper que arma Spring Boot: módulo de java.time y tolerante a campos
        // desconocidos (si el publicador agrega uno, el mensaje NO se cae a la DLQ por eso).
        ReservationEventParser parser =
                new ReservationEventParser(Jackson2ObjectMapperBuilder.json().build());
        listener = new AvailabilityEventListener(parser, availabilityService, rabbitTemplate,
                contractProperties());

        listenerLogger = (Logger) LoggerFactory.getLogger(AvailabilityEventListener.class);
        logs = new ListAppender<>();
        logs.start();
        listenerLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        listenerLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("Evento válido: se delega al dominio y se hace basicAck")
    void validEventIsAppliedAndAcked() throws Exception {
        when(availabilityService.apply(any())).thenReturn(AvailabilityOutcome.RESERVED);

        listener.onReservationEvent(message(validEnvelope("reservation.confirmed"), null), channel);

        ArgumentCaptor<ReservationEventEnvelope> captor =
                ArgumentCaptor.forClass(ReservationEventEnvelope.class);
        verify(availabilityService).apply(captor.capture());
        ReservationEventEnvelope sent = captor.getValue();
        assertThat(sent.eventId()).isEqualTo(EVENT_ID);
        assertThat(sent.type()).isEqualTo("reservation.confirmed");
        assertThat(sent.traceId()).isEqualTo("9c94d6ef");
        assertThat(sent.payload().reservationCode()).isEqualTo("RSV-000002");
        assertThat(sent.payload().unitId()).isEqualTo(1L);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        // Nada se republica: no hubo error.
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("JSON inválido: basicNack sin requeue (a la DLQ), sin reintento y con log ERROR")
    void invalidJsonGoesToTheDlqWithoutRetry() throws Exception {
        listener.onReservationEvent(message("{ esto no es json ", null), channel);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        // Ni se toca el dominio ni se republica: reintentar un mensaje corrupto no sirve de nada.
        verifyNoInteractions(availabilityService);
        verifyNoInteractions(rabbitTemplate);
        assertThat(errorLogs()).anyMatch(text -> text.contains("no recuperable")
                && text.contains("JSON inválido"));
    }

    @Test
    @DisplayName("Envelope sin unitId: basicNack sin requeue y log ERROR")
    void incompleteEnvelopeGoesToTheDlq() throws Exception {
        String incomplete = """
                {
                  "type": "reservation.confirmed",
                  "eventId": "%s",
                  "timestamp": "2026-10-17T20:15:30Z",
                  "traceId": "9c94d6ef",
                  "payload": { "reservationCode": "RSV-000009", "status": "CONFIRMADA" }
                }
                """.formatted(EVENT_ID);

        listener.onReservationEvent(message(incomplete, null), channel);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verifyNoInteractions(availabilityService);
        verifyNoInteractions(rabbitTemplate);
        assertThat(errorLogs()).anyMatch(text -> text.contains("incompleto")
                && text.contains("payload.unitId"));
    }

    @Test
    @DisplayName("Sin disponibilidad: basicNack sin requeue y log ERROR con el código de la reserva")
    void eventWithoutAvailabilityGoesToTheDlq() throws Exception {
        // Dato de negocio imposible: se confirmó una reserva sobre una unidad en 0.
        when(availabilityService.apply(any())).thenThrow(new NonRecoverableEventException(
                "sin disponibilidad para confirmar la reserva RSV-000002: la unidad 1 está en 0"));

        listener.onReservationEvent(message(validEnvelope("reservation.confirmed"), 1), channel);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        // No se reintenta: reintentar no va a crear disponibilidad.
        verifyNoInteractions(rabbitTemplate);
        // El contrato pide los tres datos en el log del descarte: eventId, traceId y el motivo.
        assertThat(errorLogs()).anyMatch(text -> text.contains("sin disponibilidad")
                && text.contains("RSV-000002") && text.contains(EVENT_ID)
                && text.contains("traceId=9c94d6ef"));
    }

    @Test
    @DisplayName("Error recuperable: republica con x-attempt + 1 y confirma el original")
    void recoverableErrorIsRepublishedWithTheNextAttempt() throws Exception {
        // Base de datos caída: el mensaje es bueno, el momento no.
        when(availabilityService.apply(any()))
                .thenThrow(new DataAccessResourceFailureException("la base no responde"));

        // Primera entrega: el header x-attempt no viene, así que vale 1.
        listener.onReservationEvent(message(validEnvelope("reservation.cancelled"), null), channel);

        ArgumentCaptor<Message> republished = ArgumentCaptor.forClass(Message.class);
        // Se republica en la PROPIA cola (exchange por defecto), no en events.topic: si no,
        // el evento le volvería a llegar a reportería y a auditoría, que ya lo procesaron.
        verify(rabbitTemplate).send(eq(""), eq(QUEUE), republished.capture());
        MessageProperties props = republished.getValue().getMessageProperties();
        // getHeader() es genérico: se guarda en un Object para que assertThat no quede ambiguo.
        Object nextAttempt = props.getHeader(ATTEMPT_HEADER);
        assertThat(nextAttempt).isEqualTo(2);
        // El reintento es el MISMO mensaje: cuerpo, messageId y entrega persistente.
        assertThat(new String(republished.getValue().getBody(), StandardCharsets.UTF_8))
                .contains(EVENT_ID);
        assertThat(props.getMessageId()).isEqualTo(EVENT_ID);
        assertThat(props.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(props.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);

        // El original se confirma porque ya existe la copia con el contador aumentado.
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("Error recuperable con los 3 intentos agotados: a la DLQ y nada de republicar")
    void recoverableErrorGoesToTheDlqWhenAttemptsAreExhausted() throws Exception {
        when(availabilityService.apply(any()))
                .thenThrow(new DataAccessResourceFailureException("la base no responde"));

        // Tercera entrega del contrato (max-attempts = 3): ya no quedan reintentos.
        listener.onReservationEvent(message(validEnvelope("reservation.confirmed"), 3), channel);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
        assertThat(errorLogs()).anyMatch(text -> text.contains("agotar los 3 intentos")
                && text.contains(EVENT_ID));
    }

    @Test
    @DisplayName("Si no se puede republicar el reintento: a la DLQ, NUNCA con requeue=true")
    void whenTheRetryCannotBePublishedTheEventGoesToTheDlq() throws Exception {
        when(availabilityService.apply(any()))
                .thenThrow(new DataAccessResourceFailureException("la base no responde"));
        // El broker tampoco acepta la publicación del reintento (flow control, conexión de
        // publicación caída mientras la del consumidor sigue viva).
        doThrow(new AmqpConnectException(new IOException("sin canal")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        listener.onReservationEvent(message(validEnvelope("reservation.confirmed"), 1), channel);

        // El mensaje se descarta a la DLQ, igual que al agotar los intentos.
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        // Lo que NO puede pasar: devolverlo a la cola con el mismo x-attempt, porque volvería a la
        // cabeza sin contador y el consumidor giraría sobre el mismo mensaje sin límite.
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), eq(true));
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(errorLogs()).anyMatch(text -> text.contains("No se pudo republicar")
                && text.contains(EVENT_ID) && text.contains("traceId=9c94d6ef")
                && text.contains("RSV-000002"));
    }

    @Test
    @DisplayName("Mismo eventId repetido: basicAck sin repetir el efecto ni republicar")
    void duplicateEventIsAcked() throws Exception {
        when(availabilityService.apply(any())).thenReturn(AvailabilityOutcome.DUPLICATE);

        listener.onReservationEvent(message(validEnvelope("reservation.confirmed"), 1), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(rabbitTemplate);
    }

    // ---------------------------------------------------------------------------------------
    //  Ayudas
    // ---------------------------------------------------------------------------------------

    /** Envelope del contrato, con el tipo de evento que se quiera probar. */
    private String validEnvelope(String type) {
        return """
                {
                  "type": "%s",
                  "eventId": "%s",
                  "timestamp": "2026-10-17T20:15:30Z",
                  "traceId": "9c94d6ef",
                  "correlationId": "RSV-000002",
                  "actor": "max.hernandezg@duocuc.cl",
                  "payload": {
                    "reservationCode": "RSV-000002", "unitId": 1, "unitName": "Cabaña Espolón",
                    "guestEmail": "ia.reyes@duocuc.cl", "guestName": "Ian Reyes",
                    "status": "CONFIRMADA", "previousStatus": "CREADA",
                    "checkIn": "2026-09-20", "checkOut": "2026-09-22", "nights": 2,
                    "totalAmount": 236000, "occurredAt": "2026-10-17T20:15:30Z"
                  }
                }
                """.formatted(type, EVENT_ID);
    }

    /** Mensaje tal como lo entrega el broker, con las propiedades AMQP del contrato. */
    private Message message(String body, Integer attempt) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(DELIVERY_TAG);
        props.setMessageId(EVENT_ID);
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setReceivedRoutingKey("reservation.confirmed");
        if (attempt != null) {
            props.setHeader(ATTEMPT_HEADER, attempt);
        }
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    private List<String> errorLogs() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
