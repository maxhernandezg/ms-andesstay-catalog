package cl.andesstay.catalog.repository;

import cl.andesstay.catalog.domain.Unit;
import cl.andesstay.catalog.domain.UnitType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acceso a datos de las unidades del catalogo.
 *
 * Los filtros de GET /api/catalog/units se resuelven con query methods derivados
 * (nada de JPQL con parametros nulos) para que se comporten igual en H2 y en Oracle.
 */
public interface UnitRepository extends JpaRepository<Unit, Long> {

    Optional<Unit> findByCode(String code);

    boolean existsByCode(String code);

    List<Unit> findAllByOrderByIdAsc();

    List<Unit> findByTypeOrderByIdAsc(UnitType type);

    List<Unit> findByActiveTrueAndAvailableStockGreaterThanOrderByIdAsc(int minStock);

    List<Unit> findByTypeAndActiveTrueAndAvailableStockGreaterThanOrderByIdAsc(UnitType type, int minStock);
}
