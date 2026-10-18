package cl.andesstay.catalog.repository;

import cl.andesstay.catalog.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de la bitácora de idempotencia. La clave es el {@code eventId} del envelope, así
 * que el {@code existsById} del CRUD básico es toda la consulta que hace falta.
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
