package cl.andesstay.catalog.security;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Decoder que difiere la construccion del decoder real hasta el primer token que llega.
 *
 * Por que existe: NimbusJwtDecoder.withIssuerLocation(...).build() sale a Internet a leer el
 * documento OpenID del tenant. Si el proyecto se levanta recien clonado con el placeholder
 * 6f522bef-27e2-4548-89e6-c1717b61ae20 (o simplemente sin red), esa llamada revienta y el contexto de Spring no
 * arranca. Con este envoltorio la aplicacion parte siempre; la resolucion del issuer
 * ocurre en la primera peticion autenticada y, si falla, se traduce en un 401 limpio en
 * vez de tumbar el servicio.
 *
 * Por que BadJwtException y no JwtException: JwtAuthenticationProvider solo convierte la
 * BadJwtException en InvalidBearerTokenException, que llega al entry point del contrato y
 * responde 401 con la ruta original. Una JwtException a secas la convierte en
 * AuthenticationServiceException; el BearerTokenAuthenticationFilter la relanza, Tomcat hace
 * forward a /error y el cliente ve un 401 con "path": "/error" y un stack trace en el log por
 * cada peticion.
 *
 * El fallo no se memoiza: si el supplier revienta, la siguiente peticion vuelve a intentarlo,
 * asi que basta con que vuelva la red (o con corregir el tenant y reiniciar) para que el
 * servicio empiece a validar tokens.
 */
public class LazyJwtDecoder implements JwtDecoder {

    private static final Logger log = LoggerFactory.getLogger(LazyJwtDecoder.class);

    private final Supplier<JwtDecoder> delegateSupplier;
    private volatile JwtDecoder delegate;

    public LazyJwtDecoder(Supplier<JwtDecoder> delegateSupplier) {
        this.delegateSupplier = delegateSupplier;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        return resolveDelegate().decode(token);
    }

    /** Memoizacion con doble chequeo: el decoder real se construye una sola vez. */
    private JwtDecoder resolveDelegate() {
        JwtDecoder current = this.delegate;
        if (current == null) {
            synchronized (this) {
                current = this.delegate;
                if (current == null) {
                    try {
                        current = this.delegateSupplier.get();
                    } catch (RuntimeException ex) {
                        // Una linea en WARN y sin stack trace: es un problema de configuracion o
                        // de red, no un bug, y se repetiria en cada peticion mientras dure.
                        log.warn("No se pudo inicializar el validador de tokens: {}. "
                                + "Revisa AZURE_TENANT_ID y la conectividad con Azure AD.", ex.getMessage());
                        // BadJwtException (ver javadoc de la clase): es la unica que termina en
                        // el 401 del contrato con la ruta original en vez de pasar por /error.
                        throw new BadJwtException("No se pudo validar el token: el validador de tokens "
                                + "no se pudo inicializar (revisa AZURE_TENANT_ID y la conectividad "
                                + "con Azure AD)", ex);
                    }
                    this.delegate = current;
                }
            }
        }
        return current;
    }
}
