package cl.andesstay.catalog.web;

import cl.andesstay.catalog.domain.UnitType;
import cl.andesstay.catalog.dto.UnitRequest;
import cl.andesstay.catalog.dto.UnitResponse;
import cl.andesstay.catalog.security.AuthenticatedUser;
import cl.andesstay.catalog.service.UnitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API del catalogo de unidades. La autorizacion por rol vive en SecurityConfig
 * (matriz del contrato, seccion 4); aqui solo va la logica HTTP.
 */
@RestController
@RequestMapping("/api/catalog/units")
@Tag(name = "Catalogo", description = "Unidades alojables y disponibilidad de AndesStay")
public class UnitController {

    private static final Logger log = LoggerFactory.getLogger(UnitController.class);

    private final UnitService service;

    public UnitController(UnitService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Lista las unidades del catalogo (roles ADMIN, OPERADOR, CLIENTE)")
    public List<UnitResponse> list(@RequestParam(name = "type", required = false) UnitType type,
                                   @RequestParam(name = "available", required = false) Boolean available) {
        return service.findAll(type, available).stream()
                .map(UnitResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtiene una unidad por id (roles ADMIN, OPERADOR, CLIENTE)")
    public UnitResponse get(@PathVariable Long id) {
        return UnitResponse.from(service.findById(id));
    }

    @PostMapping
    @Operation(summary = "Crea una unidad (solo ADMIN)")
    public ResponseEntity<UnitResponse> create(@Valid @RequestBody UnitRequest request) {
        UnitResponse created = UnitResponse.from(service.create(request));
        log.info("Unidad {} creada por {}", created.code(), AuthenticatedUser.username());
        return ResponseEntity.created(URI.create("/api/catalog/units/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualiza una unidad (solo ADMIN)")
    public UnitResponse update(@PathVariable Long id, @Valid @RequestBody UnitRequest request) {
        UnitResponse updated = UnitResponse.from(service.update(id, request));
        log.info("Unidad {} actualizada por {}", updated.code(), AuthenticatedUser.username());
        return updated;
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Elimina una unidad (solo ADMIN)")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        log.info("Unidad id={} eliminada por {}", id, AuthenticatedUser.username());
        return ResponseEntity.noContent().build();
    }
}
