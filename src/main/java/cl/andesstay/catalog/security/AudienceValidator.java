package cl.andesstay.catalog.security;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Valida que el claim 'aud' del token contenga alguna de las audiencias aceptadas.
 * Azure AD emite 'api://7d348e57-2f83-4648-baf0-588cbacccc3c' en tokens v1 y el GUID pelado en v2:
 * por eso se aceptan las dos formas (contrato, seccion 2).
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final List<String> acceptedAudiences;

    public AudienceValidator(List<String> acceptedAudiences) {
        this.acceptedAudiences = List.copyOf(acceptedAudiences);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audiences = token.getAudience();
        if (audiences != null && audiences.stream().anyMatch(acceptedAudiences::contains)) {
            return OAuth2TokenValidatorResult.success();
        }
        OAuth2Error error = new OAuth2Error(
                "invalid_token",
                "La audiencia del token no corresponde a esta API. Se esperaba alguna de: " + acceptedAudiences,
                "https://tools.ietf.org/html/rfc6750#section-3.1");
        return OAuth2TokenValidatorResult.failure(error);
    }
}
