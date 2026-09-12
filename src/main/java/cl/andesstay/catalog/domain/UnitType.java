package cl.andesstay.catalog.domain;

/**
 * Tipos de unidad alojable definidos en el contrato (seccion 5).
 * Se escriben sin tilde para evitar problemas de codificacion en la base de datos.
 */
public enum UnitType {
    HABITACION("Habitacion"),
    CABANA("Cabana"),
    LODGE("Lodge");

    private final String label;

    UnitType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
