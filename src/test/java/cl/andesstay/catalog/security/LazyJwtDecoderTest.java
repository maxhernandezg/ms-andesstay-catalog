package cl.andesstay.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Prueba unitaria del decoder perezoso, sin Spring ni red: el supplier simula el
 * NimbusJwtDecoder que no se pudo armar porque el issuer no resuelve.
 *
 * Lo que importa es el TIPO de la excepcion. Con BadJwtException Spring Security responde el
 * 401 del contrato con la ruta original; con una JwtException a secas la peticion terminaba
 * en /error (ver el javadoc de {@link LazyJwtDecoder}).
 */
class LazyJwtDecoderTest {

    @Test
    @DisplayName("Si el supplier falla, decode lanza BadJwtException y conserva la causa")
    void supplierFailureIsTranslatedToBadJwtException() {
        IllegalStateException sinRed = new IllegalStateException(
                "Unable to resolve the Configuration with the provided Issuer");
        LazyJwtDecoder decoder = new LazyJwtDecoder(() -> {
            throw sinRed;
        });

        assertThatThrownBy(() -> decoder.decode("token-cualquiera"))
                .isExactlyInstanceOf(BadJwtException.class)
                .hasMessageContaining("AZURE_TENANT_ID")
                .hasCause(sinRed);
    }

    @Test
    @DisplayName("El fallo no se memoiza: la peticion siguiente vuelve a intentar")
    void failureIsNotMemoized() {
        Jwt jwt = sampleJwt();
        JwtDecoder real = mock(JwtDecoder.class);
        when(real.decode("token-valido")).thenReturn(jwt);

        AtomicInteger intentos = new AtomicInteger();
        LazyJwtDecoder decoder = new LazyJwtDecoder(() -> {
            if (intentos.incrementAndGet() == 1) {
                throw new IllegalStateException("sin red");
            }
            return real;
        });

        assertThatThrownBy(() -> decoder.decode("token-valido"))
                .isInstanceOf(BadJwtException.class);
        assertThat(decoder.decode("token-valido")).isSameAs(jwt);
        assertThat(intentos).hasValue(2);
    }

    @Test
    @DisplayName("Una vez construido, el decoder real se reutiliza sin volver a llamar al supplier")
    void successfulDelegateIsBuiltOnce() {
        Jwt jwt = sampleJwt();
        JwtDecoder real = mock(JwtDecoder.class);
        when(real.decode("token-valido")).thenReturn(jwt);

        AtomicInteger construcciones = new AtomicInteger();
        LazyJwtDecoder decoder = new LazyJwtDecoder(() -> {
            construcciones.incrementAndGet();
            return real;
        });

        assertThat(decoder.decode("token-valido")).isSameAs(jwt);
        assertThat(decoder.decode("token-valido")).isSameAs(jwt);
        assertThat(construcciones).hasValue(1);
    }

    private static Jwt sampleJwt() {
        return Jwt.withTokenValue("token-valido")
                .header("alg", "RS256")
                .claim("sub", "usuario-prueba")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
