package cl.andesstay.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * El {@code application.yml} trae los nombres del contrato del stream, igual que
 * {@link RabbitTopologyConfigTest} lo comprueba para RabbitMQ: ningún tópico ni grupo escrito a
 * mano en el código.
 *
 * <p>Lo que más importa acá es la DLT: tiene que ser la PROPIA de este servicio,
 * {@code reservations.events.catalog.DLT}, y no la {@code reservations.events.DLT} compartida
 * que existía antes. Ese es el nombre que crea {@code infra/kafka/topics.sh}; con la
 * auto-creación de tópicos apagada, cualquier otro deja la partición trancada al primer registro
 * que falle.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AndesStayKafkaPropertiesTest {

    /** Nunca se llama: garantiza que ninguna prueba intente resolver el JWKS real de Azure. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private AndesStayKafkaProperties props;

    @Test
    @DisplayName("El yml trae el tópico, el grupo y la DLT propia del catálogo")
    void propertiesCarryTheStreamContract() {
        assertThat(props.topics().reservationEvents()).isEqualTo("reservations.events");
        assertThat(props.consumerGroup()).isEqualTo("catalog-consumer");
        assertThat(props.topics().deadLetter())
                .isEqualTo("reservations.events.catalog.DLT")
                .isNotEqualTo("reservations.events.DLT");
        assertThat(props.maxAttempts()).isEqualTo(3);
        // En el perfil de pruebas el consumidor queda apagado: ninguna prueba se une al grupo
        // real catalog-consumer aunque el clúster esté arriba en la misma máquina.
        assertThat(props.enabled()).isFalse();
    }
}
