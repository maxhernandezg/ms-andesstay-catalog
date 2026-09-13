package cl.andesstay.catalog.service;

import cl.andesstay.catalog.domain.Unit;
import cl.andesstay.catalog.domain.UnitType;
import cl.andesstay.catalog.dto.UnitRequest;
import cl.andesstay.catalog.exception.ConflictException;
import cl.andesstay.catalog.exception.NotFoundException;
import cl.andesstay.catalog.repository.UnitRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reglas de negocio del catalogo: CRUD de unidades y su disponibilidad.
 *
 * La disponibilidad se administra desde el propio CRUD (PUT, solo ADMIN). La entidad lleva
 * @Version para que dos actualizaciones simultaneas sobre la misma unidad no se pisen.
 */
@Service
public class UnitService {

    private final UnitRepository repository;

    public UnitService(UnitRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<Unit> findAll(UnitType type, Boolean onlyAvailable) {
        boolean available = Boolean.TRUE.equals(onlyAvailable);
        if (type != null && available) {
            return repository.findByTypeAndActiveTrueAndAvailableStockGreaterThanOrderByIdAsc(type, 0);
        }
        if (type != null) {
            return repository.findByTypeOrderByIdAsc(type);
        }
        if (available) {
            return repository.findByActiveTrueAndAvailableStockGreaterThanOrderByIdAsc(0);
        }
        return repository.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public Unit findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("No existe la unidad con id " + id));
    }

    @Transactional
    public Unit create(UnitRequest request) {
        if (repository.existsByCode(request.code())) {
            throw new ConflictException("Ya existe una unidad con el codigo " + request.code());
        }
        Unit unit = new Unit();
        apply(unit, request);
        // Si no viene availableStock se asume que la unidad parte con todos sus cupos libres.
        unit.setAvailableStock(request.availableStock() != null ? request.availableStock() : request.totalStock());
        validateStock(unit);
        return repository.save(unit);
    }

    @Transactional
    public Unit update(Long id, UnitRequest request) {
        Unit unit = findById(id);
        repository.findByCode(request.code())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Ya existe otra unidad con el codigo " + request.code());
                });
        apply(unit, request);
        if (request.availableStock() != null) {
            unit.setAvailableStock(request.availableStock());
        } else if (unit.getAvailableStock() > request.totalStock()) {
            // Al bajar el stock total se ajusta el disponible para no dejarlo inconsistente.
            unit.setAvailableStock(request.totalStock());
        }
        validateStock(unit);
        return repository.save(unit);
    }

    @Transactional
    public void delete(Long id) {
        Unit unit = findById(id);
        repository.delete(unit);
    }

    private void apply(Unit unit, UnitRequest request) {
        unit.setCode(request.code());
        unit.setName(request.name());
        unit.setType(request.type());
        unit.setPropertyName(request.propertyName());
        unit.setLocation(request.location());
        unit.setCapacity(request.capacity());
        unit.setNightlyRate(request.nightlyRate());
        unit.setTotalStock(request.totalStock());
        unit.setActive(request.active() == null || request.active());
    }

    private void validateStock(Unit unit) {
        if (unit.getAvailableStock() > unit.getTotalStock()) {
            throw new ConflictException("El stock disponible (" + unit.getAvailableStock()
                    + ") no puede superar al stock total (" + unit.getTotalStock() + ")");
        }
    }
}
