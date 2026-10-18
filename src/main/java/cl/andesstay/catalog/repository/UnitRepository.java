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

    // =====================================================================================
    //  Movimientos de disponibilidad por mensajería (EP3)
    //
    //  POR QUÉ UN UPDATE CONDICIONAL Y NO "leer la entidad, restar uno y guardar":
    //
    //  1. La condición del WHERE es la que protege el invariante. En un read-modify-write, entre
    //     el "if (availableStock > 0)" y el guardado hay una ventana en que otro hilo puede
    //     consumir el último cupo; con @Version el segundo commit fallaría y habría que
    //     reintentar, pero el chequeo de negocio igual se hizo sobre un dato viejo. Acá la base
    //     evalúa la condición y escribe en la MISMA sentencia atómica: el stock no puede quedar
    //     negativo ni pasarse de TOTAL_STOCK, pase lo que pase con la concurrencia.
    //  2. Devuelve la cantidad de filas afectadas, que es la respuesta que el servicio necesita:
    //     0 = "no se pudo" (sin cupo / ya está full), 1 = "se movió". Sin reintentos, sin
    //     excepciones de bloqueo y sin un segundo viaje a la base para averiguarlo.
    //  3. Igual sube VERSION: el @Version de la entidad sigue siendo la defensa del CRUD REST, y
    //     si un ADMIN estaba editando esa unidad en paralelo (PUT), su commit choca con el
    //     bloqueo optimista y recibe el 409 de siempre en vez de pisar el movimiento de stock.
    //
    //  Es SQL nativo (no JPQL) porque asignarle el campo @Version en un bulk update de HQL no es
    //  portable, y porque COALESCE(VERSION, 0) cubre las filas sembradas a mano. Los nombres de
    //  tabla y columnas son idénticos en H2 (perfil dev/test) y en Oracle ADB (perfil oracle),
    //  así que la sentencia corre igual en las dos bases.
    //
    //  clearAutomatically/flushAutomatically: el bulk update no pasa por el contexto de
    //  persistencia, así que se vacían los cambios pendientes antes y se limpia la caché de
    //  primer nivel después, para que nadie siga leyendo el stock viejo en la misma transacción.
    // =====================================================================================

    /**
     * Descuenta un cupo de la unidad, solo si queda disponibilidad.
     *
     * @return 1 si descontó; 0 si la unidad no existe o su {@code AVAILABLE_STOCK} ya está en 0
     *         (en ese caso NO se escribe nada: el stock nunca queda negativo)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE CATALOG_UNIT
               SET AVAILABLE_STOCK = AVAILABLE_STOCK - 1,
                   VERSION         = COALESCE(VERSION, 0) + 1,
                   UPDATED_AT      = :now
             WHERE ID = :unitId
               AND AVAILABLE_STOCK > 0
            """, nativeQuery = true)
    int reserveOneIfAvailable(@Param("unitId") Long unitId, @Param("now") Instant now);

    /**
     * Devuelve un cupo a la unidad, sin pasarse nunca del stock total.
     *
     * @return 1 si liberó; 0 si la unidad no existe o ya tenía todos sus cupos libres (una
     *         reserva que se cancela sin haber estado confirmada nunca consumió disponibilidad)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE CATALOG_UNIT
               SET AVAILABLE_STOCK = AVAILABLE_STOCK + 1,
                   VERSION         = COALESCE(VERSION, 0) + 1,
                   UPDATED_AT      = :now
             WHERE ID = :unitId
               AND AVAILABLE_STOCK < TOTAL_STOCK
            """, nativeQuery = true)
    int releaseOneIfNotFull(@Param("unitId") Long unitId, @Param("now") Instant now);
}
