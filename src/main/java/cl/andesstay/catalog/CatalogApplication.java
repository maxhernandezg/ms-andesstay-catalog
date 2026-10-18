package cl.andesstay.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Punto de entrada del microservicio de catalogo de AndesStay (puerto 8082).
 * Expone las unidades alojables (habitaciones, cabanas y lodges) y su disponibilidad.
 *
 * <p>Desde la EP3 la disponibilidad también se mueve por mensajería: el consumidor
 * {@code cl.andesstay.catalog.messaging.AvailabilityEventListener} toma los eventos de la cola
 * {@code q.evt.catalog} y descuenta un cupo al confirmarse una reserva o lo libera al
 * cancelarse, sin que ms-andesstay-reservations llame a este servicio.</p>
 *
 * <p>{@code @ConfigurationPropertiesScan} es lo que registra
 * {@link cl.andesstay.catalog.config.AndesStayRabbitProperties}, donde vive el contrato de
 * mensajería leído del {@code application.yml}.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
