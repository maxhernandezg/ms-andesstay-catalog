package cl.andesstay.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Unidad alojable del catalogo (tabla CATALOG_UNIT).
 *
 * El campo {@code version} habilita el bloqueo optimista de JPA: si dos operaciones
 * de reserva/liberacion tocan la misma fila al mismo tiempo, la segunda falla con
 * OptimisticLockingFailureException y el servicio la reintenta. Es la estrategia mas
 * portable entre H2 (perfil dev) y Oracle ADB (perfil oracle).
 */
@Entity
@Table(name = "CATALOG_UNIT")
public class Unit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "CODE", nullable = false, unique = true, length = 20)
    private String code;

    @Column(name = "NAME", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "TYPE", nullable = false, length = 20)
    private UnitType type;

    @Column(name = "PROPERTY_NAME", nullable = false, length = 120)
    private String propertyName;

    @Column(name = "LOCATION", nullable = false, length = 120)
    private String location;

    @Column(name = "CAPACITY", nullable = false)
    private Integer capacity;

    @Column(name = "NIGHTLY_RATE", nullable = false, precision = 12, scale = 2)
    private BigDecimal nightlyRate;

    @Column(name = "TOTAL_STOCK", nullable = false)
    private Integer totalStock;

    @Column(name = "AVAILABLE_STOCK", nullable = false)
    private Integer availableStock;

    @Column(name = "ACTIVE", nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "VERSION")
    private Long version;

    public Unit() {
        // Constructor requerido por JPA
    }

    public Unit(String code, String name, UnitType type, String propertyName, String location,
                Integer capacity, BigDecimal nightlyRate, Integer totalStock, Integer availableStock,
                Boolean active) {
        this.code = code;
        this.name = name;
        this.type = type;
        this.propertyName = propertyName;
        this.location = location;
        this.capacity = capacity;
        this.nightlyRate = nightlyRate;
        this.totalStock = totalStock;
        this.availableStock = availableStock;
        this.active = active;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.active == null) {
            this.active = Boolean.TRUE;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UnitType getType() {
        return type;
    }

    public void setType(UnitType type) {
        this.type = type;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public void setPropertyName(String propertyName) {
        this.propertyName = propertyName;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public BigDecimal getNightlyRate() {
        return nightlyRate;
    }

    public void setNightlyRate(BigDecimal nightlyRate) {
        this.nightlyRate = nightlyRate;
    }

    public Integer getTotalStock() {
        return totalStock;
    }

    public void setTotalStock(Integer totalStock) {
        this.totalStock = totalStock;
    }

    public Integer getAvailableStock() {
        return availableStock;
    }

    public void setAvailableStock(Integer availableStock) {
        this.availableStock = availableStock;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
