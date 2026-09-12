package cl.andesstay.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Prueba de integración REAL contra la Oracle Autonomous Database (perfil {@code oracle}).
 *
 * <p>Demuestra la regla clave del módulo "Catálogo" del caso: <b>la disponibilidad disminuye
 * al confirmar la reserva</b>. Al CONFIRMAR, {@code ms-andesstay-reservations} llama a
 * {@code POST /api/catalog/units/{id}/reserve} con {@code {"quantity":1}}; eso es exactamente
 * lo que se ejecuta aquí, y el {@code AVAILABLE_STOCK} se lee con SQL crudo antes y después,
 * sin pasar por el repositorio JPA.</p>
 *
 * <p>La prueba DEVUELVE el cupo con {@code /release} al final, así que es repetible y no
 * altera el catálogo del curso.</p>
 *
 * <h2>Cómo se corre</h2>
 * <pre>
 * export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
 * export TNS_ADMIN=/Users/maximiliano/Documents/Andesstay/infra/oracle/wallet/Wallet_Andesstay
 * export ORACLE_SERVICE=andesstay_low
 * export ORACLE_USER=ANDESSTAY_CATALOG
 * export ORACLE_PASSWORD='...'   # vive solo en infra/apps/.env, NUNCA en el repo
 * ./mvnw -B test -Dtest=UnitOracleStockIntegrationTest
 * </pre>
 *
 * <p>Sin {@code ORACLE_PASSWORD} la clase queda "skipped", así que
 * {@code ./mvnw -B clean verify} sigue verde en una máquina sin wallet.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("oracle")
@EnabledIfEnvironmentVariable(named = "ORACLE_PASSWORD", matches = ".+")
class UnitOracleStockIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** El decoder real saldría al JWKS de Azure; aquí va mockeado. La base sí es la real. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("POST /units/{id}/reserve descuenta AVAILABLE_STOCK en Oracle y /release lo devuelve")
    void reserveDecrementsAvailableStockInOracle() throws Exception {
        Long unitId = jdbcTemplate.queryForObject(
                "select min(ID) from CATALOG_UNIT where AVAILABLE_STOCK > 0", Long.class);
        assertThat(unitId)
                .as("hace falta al menos una unidad con cupos en ANDESSTAY_CATALOG.CATALOG_UNIT")
                .isNotNull();

        int before = stockInDatabase(unitId);
        System.out.println("[oracle-it] unidad " + unitId + " AVAILABLE_STOCK antes = " + before);

        // --- CONFIRMAR una reserva se traduce en esto: descontar 1 cupo ---
        mockMvc.perform(post("/api/catalog/units/{id}/reserve", unitId)
                        .with(operadorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableStock").value(before - 1));

        int afterReserve = stockInDatabase(unitId);
        System.out.println("[oracle-it] unidad " + unitId + " AVAILABLE_STOCK después = "
                + afterReserve);
        assertThat(afterReserve)
                .as("la disponibilidad debe DISMINUIR en la base al confirmar")
                .isEqualTo(before - 1);

        // --- y una cancelación devuelve el cupo: la prueba queda limpia ---
        mockMvc.perform(post("/api/catalog/units/{id}/release", unitId)
                        .with(operadorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableStock").value(before));
        assertThat(stockInDatabase(unitId)).isEqualTo(before);
    }

    @Test
    @DisplayName("El huésped (CLIENTE) no puede descontar disponibilidad: 403")
    void clienteCannotReserveStock() throws Exception {
        Long unitId = jdbcTemplate.queryForObject(
                "select min(ID) from CATALOG_UNIT where AVAILABLE_STOCK > 0", Long.class);
        int before = stockInDatabase(unitId);

        mockMvc.perform(post("/api/catalog/units/{id}/reserve", unitId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENTE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isForbidden());

        assertThat(stockInDatabase(unitId))
                .as("un 403 no puede haber tocado la disponibilidad")
                .isEqualTo(before);
    }

    /** Recepcionista: el rol que el contrato exige para /reserve y /release. */
    private static JwtRequestPostProcessor operadorJwt() {
        return jwt()
                .jwt(builder -> builder.claim("preferred_username", "recepcion@andesstay.cl"))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"));
    }

    private int stockInDatabase(Long unitId) {
        Integer stock = jdbcTemplate.queryForObject(
                "select AVAILABLE_STOCK from CATALOG_UNIT where ID = ?", Integer.class, unitId);
        return stock == null ? -1 : stock;
    }
}
