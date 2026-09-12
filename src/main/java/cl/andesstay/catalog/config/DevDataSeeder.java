package cl.andesstay.catalog.config;

import cl.andesstay.catalog.domain.Unit;
import cl.andesstay.catalog.domain.UnitType;
import cl.andesstay.catalog.repository.UnitRepository;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Datos de ejemplo para el perfil dev (H2 en memoria): unidades reales de la red
 * de hostales, cabanas y lodges de AndesStay entre el Elqui y la Patagonia.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final UnitRepository repository;

    public DevDataSeeder(UnitRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            return;
        }

        List<Unit> units = List.of(
                unit("UN-PUC-001", "Habitacion Doble Volcan", UnitType.HABITACION,
                        "Hostal Ruka Pucon", "Pucon, La Araucania", 2, "68000", 8, 8),
                unit("UN-PUC-002", "Habitacion Familiar Quetrupillan", UnitType.HABITACION,
                        "Hostal Ruka Pucon", "Pucon, La Araucania", 4, "96000", 4, 3),
                unit("UN-PUC-003", "Cabana Lago Villarrica", UnitType.CABANA,
                        "Cabanas Trawun", "Pucon, La Araucania", 6, "145000", 5, 5),
                unit("UN-PVA-001", "Habitacion Superior Osorno", UnitType.HABITACION,
                        "Hostal Puerto Varas Centro", "Puerto Varas, Los Lagos", 2, "72000", 10, 9),
                unit("UN-PVA-002", "Cabana Frutillar Vista Lago", UnitType.CABANA,
                        "Cabanas Llanquihue", "Puerto Varas, Los Lagos", 5, "132000", 6, 6),
                unit("UN-PVA-003", "Lodge Calbuco Deluxe", UnitType.LODGE,
                        "AndesStay Lodge Llanquihue", "Puerto Varas, Los Lagos", 8, "285000", 3, 2),
                unit("UN-FUT-001", "Lodge Rio Futaleufu", UnitType.LODGE,
                        "AndesStay Lodge Futaleufu", "Futaleufu, Los Lagos", 10, "320000", 2, 2),
                unit("UN-FUT-002", "Cabana Espolon", UnitType.CABANA,
                        "Cabanas Espolon", "Futaleufu, Los Lagos", 4, "118000", 4, 4),
                unit("UN-COC-001", "Cabana Valle Cochamo", UnitType.CABANA,
                        "Refugio Cochamo", "Cochamo, Los Lagos", 4, "125000", 4, 1),
                unit("UN-COC-002", "Habitacion Compartida Trekking", UnitType.HABITACION,
                        "Refugio Cochamo", "Cochamo, Los Lagos", 6, "42000", 12, 12),
                unit("UN-CHI-001", "Palafito Doble Gamboa", UnitType.HABITACION,
                        "Palafitos Castro", "Castro, Chiloe", 2, "78000", 6, 5),
                unit("UN-CHI-002", "Cabana Cucao Parque Nacional", UnitType.CABANA,
                        "Cabanas Cucao", "Chonchi, Chiloe", 5, "110000", 4, 4),
                unit("UN-SPA-001", "Lodge Ayllu Atacama", UnitType.LODGE,
                        "AndesStay Lodge Atacama", "San Pedro de Atacama, Antofagasta", 8, "265000", 3, 3),
                unit("UN-SPA-002", "Habitacion Matrimonial Licancabur", UnitType.HABITACION,
                        "Hostal Licancabur", "San Pedro de Atacama, Antofagasta", 2, "85000", 9, 7),
                unit("UN-ELQ-001", "Cabana Observatorio Elqui", UnitType.CABANA,
                        "Cabanas Valle del Elqui", "Vicuna, Coquimbo", 4, "98000", 5, 5)
        );

        repository.saveAll(units);
        log.info("Perfil dev: se cargaron {} unidades de ejemplo en el catalogo", units.size());
    }

    private Unit unit(String code, String name, UnitType type, String propertyName, String location,
                      int capacity, String nightlyRate, int totalStock, int availableStock) {
        return new Unit(code, name, type, propertyName, location, capacity,
                new BigDecimal(nightlyRate), totalStock, availableStock, Boolean.TRUE);
    }
}
