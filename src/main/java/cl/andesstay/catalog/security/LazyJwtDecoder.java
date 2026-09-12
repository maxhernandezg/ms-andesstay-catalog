package cl.andesstay.catalog.security;

import java.util.function.Supplier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Decoder que difiere la construccion del decoder real hasta el primer token que llega.
 *
 * Por que existe: JwtDecoders.fromIssuerLocation() sale a Internet a leer el documento
 * OpenID del tenant. Si el proyecto se levanta recien clonado con el placeholder
 * 6f522bef-27e2-4548-89e6-c1717b61ae20 (o simplemente sin red), esa llamada revienta y el contexto de Spring no
 * arranca. Con este envoltorio la aplicacion parte siempre; la resolucion del issuer
 * ocurre en la primera peticion autenticada y, si falla, se traduce en un 401 limpio en
 * vez de tumbar el servicio.
 */
public class LazyJwtDecoder implements JwtDecoder {

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
                        throw new JwtException("No se pudo inicializar el validador de tokens. "
                                + "Revisa AZURE_TENANT_ID y la conectividad con Azure AD: " + ex.getMessage(), ex);
                    }
                    this.delegate = current;
                }
            }
        }
        return current;
    }
}
