package cl.andesstay.catalog.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración CENTRALIZADA de la mensajería de este servicio: todos los nombres de exchange,
 * cola y routing key del contrato de la EP3 se leen desde {@code andesstay.rabbit.*} del
 * {@code application.yml}.
 *
 * <p>¿Por qué un @ConfigurationProperties y no constantes en el código? Porque los nombres son
 * parte del <em>contrato común</em> del equipo: tienen que poder cambiar en un solo archivo (o
 * por variable de entorno) sin recompilar ni buscar literales repartidos por las clases de
 * negocio. Ninguna clase de este servicio escribe un nombre de cola a mano: la topología se arma
 * en {@link RabbitTopologyConfig}, el consumidor resuelve la cola con el placeholder
 * {@code ${andesstay.rabbit.queues.catalog}} y hasta la decisión de "este evento descuenta o
 * libera" se toma comparando con
 * {@link RoutingKeys#reservationConfirmed()} / {@link RoutingKeys#reservationCancelled()}.</p>
 *
 * <p>Este servicio declara SOLO su parte del contrato: {@code events.topic},
 * {@code q.evt.catalog}, {@code events.dead.dlx} y {@code q.evt.catalog.dlq}.
 * {@code q.evt.report} es de ms-andesstay-report, {@code q.evt.audit} de ms-andesstay-audit y
 * las colas de comandos de ms-andesstay-notify; declararlas acá sería invadir otro servicio y,
 * peor, podría crearlas con argumentos distintos a los que espera su dueño.</p>
 *
 * <p>Las claves de los grupos anidados NO tienen valor por defecto a propósito: si alguien borra
 * una del yml, el arranque falla con un error de binding claro en vez de declarar media
 * topología en silencio.</p>
 */
@ConfigurationProperties(prefix = "andesstay.rabbit")
public record AndesStayRabbitProperties(

        /**
         * Interruptor de la mensajería. En false el {@code @RabbitListener} no arranca (es lo que
         * hace run-local.sh cuando no hay broker, y lo que usan las pruebas para no tocar la red).
         */
        @DefaultValue("true") boolean enabled,

        /**
         * Máximo de intentos de un mensaje antes de irse a la DLQ. Es el mismo número que publica
         * rabbit-admin en {@code /api/rabbit/topology}: el contrato de reintentos es uno solo.
         */
        @DefaultValue("3") int maxAttempts,

        /** Header del contrato que lleva el número de intento (1 en la primera entrega). */
        @DefaultValue("x-attempt") String attemptHeader,

        Exchanges exchanges,
        Queues queues,
        RoutingKeys routingKeys) {

    /** Los dos exchanges de eventos que le interesan al catálogo (ambos durables). */
    public record Exchanges(String eventsTopic, String eventsDlx) {
    }

    /** La cola de eventos de este servicio y su cola de descarte. */
    public record Queues(String catalog, String catalogDlq) {
    }

    /**
     * Routing keys del contrato que usa el catálogo.
     *
     * <p>A diferencia de reportería y auditoría, que se suscriben con el patrón
     * {@code reservation.#}, acá los bindings son las dos claves EXACTAS que mueven el stock.
     * Si mañana aparece un {@code reservation.noshow} que también deba liberar cupos, hay que
     * agregarle su binding a propósito: es preferible a que un evento nuevo empiece a tocar la
     * disponibilidad sin que nadie lo haya decidido.</p>
     */
    public record RoutingKeys(String reservationConfirmed, String reservationCancelled,
                              String catalogDead) {

        /** Los dos eventos a los que está atada {@code q.evt.catalog}. */
        public List<String> boundEvents() {
            return List.of(reservationConfirmed, reservationCancelled);
        }
    }
}
