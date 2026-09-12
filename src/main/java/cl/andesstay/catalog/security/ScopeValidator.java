package cl.andesstay.catalog.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

/**
 * Valida que el claim 'scp' contenga el scope delegado exigido (access_as_user).
 */
public class ScopeValidator implements OAuth2TokenValidator<Jwt> {

    private final String requiredScope;

    public ScopeValidator(String requiredScope) {
        this.requiredScope = requiredScope;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String scp = token.getClaimAsString("scp");
        if (StringUtils.hasText(scp)) {
            List<String> scopes = Arrays.asList(scp.split(" "));
            if (scopes.contains(requiredScope)) {
                return OAuth2TokenValidatorResult.success();
            }
        }
        OAuth2Error error = new OAuth2Error(
                "insufficient_scope",
                "El token no contiene el scope requerido: " + requiredScope,
                "https://tools.ietf.org/html/rfc6750#section-3.1");
        return OAuth2TokenValidatorResult.failure(error);
    }
}
