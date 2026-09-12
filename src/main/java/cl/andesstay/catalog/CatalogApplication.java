package cl.andesstay.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada del microservicio de catalogo de AndesStay (puerto 8082).
 * Expone las unidades alojables (habitaciones, cabanas y lodges) y su disponibilidad.
 */
@SpringBootApplication
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
