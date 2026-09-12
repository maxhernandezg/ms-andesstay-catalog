package cl.andesstay.catalog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body de /reserve y /release: {"quantity": 1}
 */
public record QuantityRequest(
        @NotNull(message = "La cantidad es obligatoria")
        @Min(value = 1, message = "La cantidad minima es 1")
        @Max(value = 50, message = "La cantidad maxima por operacion es 50")
        Integer quantity
) {
}
