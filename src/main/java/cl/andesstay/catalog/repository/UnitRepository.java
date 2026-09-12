package cl.andesstay.catalog.repository;

import cl.andesstay.catalog.domain.Unit;
import cl.andesstay.catalog.domain.UnitType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Descuento atomico de cupos: la condicion availableStock >= :quantity viaja dentro del
     * WHERE, de modo que es la base de datos la que garantiza que jamas quede stock negativo,
     * incluso con varias reservas simultaneas. Devuelve las filas afectadas (0 = sin cupos).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Unit u
               set u.availableStock = u.availableStock - :quantity,
                   u.updatedAt = :now
             where u.id = :id
               and u.availableStock >= :quantity
            """)
    int decrementStock(@Param("id") Long id, @Param("quantity") int quantity, @Param("now") Instant now);

    /**
     * Devolucion atomica de cupos: la condicion del WHERE impide que availableStock
     * termine por sobre totalStock.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Unit u
               set u.availableStock = u.availableStock + :quantity,
                   u.updatedAt = :now
             where u.id = :id
               and u.availableStock + :quantity <= u.totalStock
            """)
    int incrementStock(@Param("id") Long id, @Param("quantity") int quantity, @Param("now") Instant now);
}
