package cl.andesstay.catalog.dto;

import cl.andesstay.catalog.domain.UnitType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Payload de creacion/actualizacion de una unidad. Solo lo usa el rol ADMIN.
 */
public record UnitRequest(
        @NotBlank(message = "El codigo es obligatorio")
        @Size(max = 20, message = "El codigo no puede superar los 20 caracteres")
        String code,

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 120, message = "El nombre no puede superar los 120 caracteres")
        String name,

        @NotNull(message = "El tipo es obligatorio (HABITACION, CABANA o LODGE)")
        UnitType type,

        @NotBlank(message = "El nombre del recinto es obligatorio")
        @Size(max = 120, message = "El nombre del recinto no puede superar los 120 caracteres")
        String propertyName,

        @NotBlank(message = "La localidad es obligatoria")
        @Size(max = 120, message = "La localidad no puede superar los 120 caracteres")
        String location,

        @NotNull(message = "La capacidad es obligatoria")
        @Min(value = 1, message = "La capacidad minima es 1 persona")
        Integer capacity,

        @NotNull(message = "La tarifa por noche es obligatoria")
        @DecimalMin(value = "0.0", inclusive = false, message = "La tarifa por noche debe ser mayor que cero")
        BigDecimal nightlyRate,

        @NotNull(message = "El stock total es obligatorio")
        @Min(value = 1, message = "El stock total minimo es 1")
        Integer totalStock,

        @Min(value = 0, message = "El stock disponible no puede ser negativo")
        Integer availableStock,

        Boolean active
) {
}
