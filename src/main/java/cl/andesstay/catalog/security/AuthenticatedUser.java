package cl.andesstay.catalog.security;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;

/**
 * Utilidad para leer el usuario autenticado desde el JWT del SecurityContext.
 * El nombre de usuario sigue el orden del contrato: preferred_username -> upn -> email -> sub.
 */
public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    public static Optional<Jwt> currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return Optional.of(jwtAuthentication.getToken());
        }
        return Optional.empty();
    }

    public static String username() {
        return currentJwt().map(AuthenticatedUser::usernameOf).orElse("anonimo");
    }

    public static String usernameOf(Jwt jwt) {
        for (String claim : List.of("preferred_username", "upn", "email")) {
            String value = jwt.getClaimAsString(claim);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return jwt.getSubject();
    }

    public static String displayName() {
        return currentJwt()
                .map(jwt -> jwt.getClaimAsString("name"))
                .filter(StringUtils::hasText)
                .orElseGet(AuthenticatedUser::username);
    }

    /** Roles sin el prefijo ROLE_ (ADMIN, OPERADOR, CLIENTE, AUDITOR). */
    public static List<String> roles() {
        return authorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .toList();
    }

    /** Scopes sin el prefijo SCOPE_ (access_as_user). */
    public static List<String> scopes() {
        return authorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("SCOPE_"))
                .map(authority -> authority.substring("SCOPE_".length()))
                .toList();
    }

    private static Collection<? extends GrantedAuthority> authorities() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getAuthorities() : List.of();
    }
}
