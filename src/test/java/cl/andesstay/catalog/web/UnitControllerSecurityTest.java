package cl.andesstay.catalog.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.andesstay.catalog.domain.Unit;
import cl.andesstay.catalog.domain.UnitType;
import cl.andesstay.catalog.repository.UnitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * Pruebas de la matriz de roles del contrato. No tocan la red: el JwtDecoder real
 * se reemplaza por un mock y los tokens se simulan con spring-security-test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnitControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UnitRepository repository;

    /** El decoder real saldria a Internet a buscar el JWKS de Azure: aqui va mockeado. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Long unitId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        Unit unit = repository.save(new Unit("UN-TEST-001", "Cabana de prueba", UnitType.CABANA,
                "Cabanas Test", "Pucon, La Araucania", 4, new BigDecimal("120000"), 5, 5, Boolean.TRUE));
        this.unitId = unit.getId();
    }

    @Test
    @DisplayName("401 sin token en GET /api/catalog/units")
    void listWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/catalog/units"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/catalog/units"));
    }

    @Test
    @DisplayName("401 sin token en POST /api/catalog/units")
    void createWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/catalog/units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayload())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("200 con ROLE_CLIENTE en GET /api/catalog/units")
    void listWithClienteRoleReturns200() throws Exception {
        mockMvc.perform(get("/api/catalog/units")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENTE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("UN-TEST-001"))
                .andExpect(jsonPath("$[0].typeLabel").value("Cabana"));
    }

    @Test
    @DisplayName("200 con ROLE_CLIENTE en GET /api/catalog/units/{id}")
    void getByIdWithClienteRoleReturns200() throws Exception {
        mockMvc.perform(get("/api/catalog/units/" + unitId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENTE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableStock").value(5));
    }

    @Test
    @DisplayName("403 con ROLE_CLIENTE en POST /api/catalog/units")
    void createWithClienteRoleReturns403() throws Exception {
        mockMvc.perform(post("/api/catalog/units")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENTE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayload())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("201 con ROLE_ADMIN en POST /api/catalog/units")
    void createWithAdminRoleReturns201() throws Exception {
        mockMvc.perform(post("/api/catalog/units")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayload())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("UN-NEW-001"))
                .andExpect(jsonPath("$.availableStock").value(4));
    }

    @Test
    @DisplayName("400 con ROLE_ADMIN si el payload es invalido")
    void createWithInvalidPayloadReturns400() throws Exception {
        Map<String, Object> payload = validPayload();
        payload.put("code", "");
        payload.put("capacity", 0);

        mockMvc.perform(post("/api/catalog/units")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("404 al pedir una unidad inexistente")
    void getMissingUnitReturns404() throws Exception {
        mockMvc.perform(get("/api/catalog/units/999999")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("/actuator/health es publico")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    private Map<String, Object> validPayload() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("code", "UN-NEW-001");
        payload.put("name", "Lodge de prueba");
        payload.put("type", "LODGE");
        payload.put("propertyName", "AndesStay Lodge Test");
        payload.put("location", "Puerto Varas, Los Lagos");
        payload.put("capacity", 6);
        payload.put("nightlyRate", 210000);
        payload.put("totalStock", 4);
        payload.put("active", true);
        return payload;
    }
}
