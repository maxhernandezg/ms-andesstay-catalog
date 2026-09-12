package cl.andesstay.catalog.dto;

import cl.andesstay.catalog.domain.Unit;
import cl.andesstay.catalog.domain.UnitType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Vista publica de una unidad del catalogo.
 */
public record UnitResponse(
        Long id,
        String code,
        String name,
        UnitType type,
        String typeLabel,
        String propertyName,
        String location,
        Integer capacity,
        BigDecimal nightlyRate,
        Integer totalStock,
        Integer availableStock,
        Boolean active,
        Instant createdAt,
        Instant updatedAt
) {

    public static UnitResponse from(Unit unit) {
        return new UnitResponse(
                unit.getId(),
                unit.getCode(),
                unit.getName(),
                unit.getType(),
                unit.getType() != null ? unit.getType().getLabel() : null,
                unit.getPropertyName(),
                unit.getLocation(),
                unit.getCapacity(),
                unit.getNightlyRate(),
                unit.getTotalStock(),
                unit.getAvailableStock(),
                unit.getActive(),
                unit.getCreatedAt(),
                unit.getUpdatedAt());
    }
}
