package cl.andesstay.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración centralizada del consumidor del stream: tópico, consumer group y política de
 * reintentos.
 *
 * <p>Mismo criterio que {@link AndesStayRabbitProperties}: <strong>ningún nombre de tópico ni de
 * grupo se escribe a mano en una clase Java</strong>. Viven en {@code application.yml} bajo
 * {@code andesstay.kafka.*} y de ahí los leen la configuración del contenedor y la anotación
 * {@code @KafkaListener}.</p>
 *
 * <h2>Qué significa el consumer group</h2>
 * <p>{@code catalog-consumer} es el nombre con que este servicio se presenta ante el clúster.
 * Kafka guarda el offset de cada partición <strong>por grupo</strong>, así que:</p>
 * <ul>
 *   <li>report, catalog, notify y el WebSocket tienen grupos <strong>distintos</strong> y cada
 *       uno recibe <strong>todos</strong> los eventos: eso es publish/subscribe;</li>
 *   <li>dos instancias de catalog comparten el <strong>mismo</strong> grupo y se reparten las
 *       particiones: eso es balanceo de carga, y es lo que permite escalar el catálogo sin tocar
 *       al productor;</li>
 *   <li>si una instancia se cae, el clúster reasigna sus particiones a las que quedan y el
 *       trabajo sigue desde el último offset confirmado, sin perder eventos ni repetir los ya
 *       aplicados.</li>
 * </ul>
 *
 * @param enabled       {@code false} deja el consumidor detenido y el servicio arranca igual
 *                      (sirve para correr sin clúster de Kafka)
 * @param consumerGroup grupo de este servicio; ver arriba
 * @param clientId      identificador de la instancia en las métricas del broker y en Kafka UI
 * @param concurrency   consumidores en paralelo dentro de ESTA instancia. Con 3 particiones y
 *                      concurrencia 3, cada hilo toma una partición y las tres se procesan a la
 *                      vez conservando el orden dentro de cada una. Más hilos que particiones
 *                      deja hilos ociosos: Kafka nunca asigna dos consumidores del mismo grupo
 *                      a la misma partición
 * @param maxAttempts   intentos totales antes de mandar el registro a la DLT (el contrato usa 3,
 *                      igual que en RabbitMQ)
 * @param retryBackoffMs espera entre intentos, en milisegundos
 * @param topics        nombres de los tópicos
 */
@ConfigurationProperties(prefix = "andesstay.kafka")
public record AndesStayKafkaProperties(
        boolean enabled,
        String consumerGroup,
        String clientId,
        int concurrency,
        int maxAttempts,
        long retryBackoffMs,
        Topics topics) {

    /**
     * @param reservationEvents tópico de los hechos de reserva (3 particiones, 7 días)
     */
    public record Topics(String reservationEvents) {
    }
}
