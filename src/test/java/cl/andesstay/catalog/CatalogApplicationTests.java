package cl.andesstay.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Comprueba que el contexto levanta con el issuer de Azure sin salir a la red
 * (gracias al JwtDecoder perezoso).
 */
@SpringBootTest
@ActiveProfiles("test")
class CatalogApplicationTests {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("el contexto de Spring arranca")
    void contextLoads() {
    }
}
