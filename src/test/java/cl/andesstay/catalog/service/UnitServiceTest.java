package cl.andesstay.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cl.andesstay.catalog.domain.Unit;
import cl.andesstay.catalog.domain.UnitType;
import cl.andesstay.catalog.dto.UnitRequest;
import cl.andesstay.catalog.exception.ConflictException;
import cl.andesstay.catalog.exception.NotFoundException;
import cl.andesstay.catalog.repository.UnitRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Pruebas del servicio de unidades contra H2 (sin red, sin Azure).
 */
@DataJpaTest
@Import(UnitService.class)
@ActiveProfiles("test")
class UnitServiceTest {

    @Autowired
    private UnitService service;

    @Autowired
    private UnitRepository repository;

    private Long unitId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        Unit unit = repository.save(new Unit("UN-SRV-001", "Cabana Cochamo", UnitType.CABANA,
                "Refugio Cochamo", "Cochamo, Los Lagos", 4, new BigDecimal("125000"), 5, 3, Boolean.TRUE));
        this.unitId = unit.getId();
    }

    @Test
    @DisplayName("create sin availableStock deja la unidad con todos sus cupos libres")
    void createDefaultsAvailableStockToTotalStock() {
        UnitRequest request = new UnitRequest("UN-SRV-002", "Lodge Atacama", UnitType.LODGE,
                "AndesStay Lodge Atacama", "San Pedro de Atacama, Antofagasta", 8,
                new BigDecimal("265000"), 3, null, true);

        Unit created = service.create(request);

        assertThat(created.getAvailableStock()).isEqualTo(3);
        assertThat(created.getId()).isNotNull();
    }

    @Test
    @DisplayName("create con codigo repetido lanza ConflictException")
    void createWithDuplicatedCodeThrowsConflict() {
        UnitRequest request = new UnitRequest("UN-SRV-001", "Otra unidad", UnitType.HABITACION,
                "Hostal Ruka Pucon", "Pucon, La Araucania", 2, new BigDecimal("68000"), 4, 4, true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("UN-SRV-001");
    }

    @Test
    @DisplayName("los filtros por tipo y disponibilidad devuelven solo lo pedido")
    void findAllAppliesFilters() {
        repository.save(new Unit("UN-SRV-003", "Habitacion sin cupos", UnitType.HABITACION,
                "Hostal Licancabur", "San Pedro de Atacama, Antofagasta", 2,
                new BigDecimal("85000"), 4, 0, Boolean.TRUE));

        List<Unit> cabanas = service.findAll(UnitType.CABANA, null);
        assertThat(cabanas).extracting(Unit::getCode).containsExactly("UN-SRV-001");

        List<Unit> disponibles = service.findAll(null, true);
        assertThat(disponibles).extracting(Unit::getCode).containsExactly("UN-SRV-001");

        List<Unit> todas = service.findAll(null, false);
        assertThat(todas).hasSize(2);
    }
}
