package cl.andesstay.catalog.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/**
 * Seguridad del microservicio de catalogo: resource server OAuth2 contra Azure AD.
 *
 * Valida firma (JWKS via issuer-uri), issuer, audiencia, vigencia y scope, y aplica la
 * matriz de roles exacta del contrato (seccion 4).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AzureAdProperties.class)
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";
    private static final String OPERADOR = "OPERADOR";
    private static final String CLIENTE = "CLIENTE";

    private final AzureAdProperties properties;

    public SecurityConfig(AzureAdProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtDecoder jwtDecoder,
                                           ObjectMapper objectMapper) throws Exception {
        RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(objectMapper);
        RestAccessDeniedHandler accessDeniedHandler = new RestAccessDeniedHandler(objectMapper);

        http
                // API sin cookies: no hay CSRF que proteger y la sesion es stateless.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Endpoints publicos: salud, info y documentacion OpenAPI.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Matriz de roles del contrato para /api/catalog
                        .requestMatchers(HttpMethod.GET, "/api/catalog/units", "/api/catalog/units/*")
                        .hasAnyRole(ADMIN, OPERADOR, CLIENTE)
                        .requestMatchers(HttpMethod.POST, "/api/catalog/units/*/reserve", "/api/catalog/units/*/release")
                        .hasAnyRole(ADMIN, OPERADOR)
                        .requestMatchers(HttpMethod.POST, "/api/catalog/units").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/catalog/units/*").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.DELETE, "/api/catalog/units/*").hasRole(ADMIN)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }

    /**
     * Decoder perezoso: la app arranca aunque el tenant sea el placeholder 6f522bef-27e2-4548-89e6-c1717b61ae20
     * o no haya red. La cadena de validadores es la exigida por el contrato:
     * defaults con issuer (firma + iss + exp/nbf) + audiencia + scope.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        String issuerUri = properties.getIssuerUri();
        List<String> audiences = properties.acceptedAudiences();
        String requiredScope = properties.getRequiredScope();

        return new LazyJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
            List<OAuth2TokenValidator<Jwt>> validators = List.of(
                    JwtValidators.createDefaultWithIssuer(issuerUri),
                    new AudienceValidator(audiences),
                    new ScopeValidator(requiredScope));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
            return decoder;
        });
    }

    /**
     * claim 'roles' -> ROLE_<valor> ; claim 'scp' -> SCOPE_<valor>
     */
    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        // Se construye a mano (en vez de JwtAuthenticationConverter) porque el nombre del
        // principal sigue el orden preferred_username -> upn -> email -> sub del contrato,
        // y eso no se expresa con un unico principalClaimName.
        return jwt -> new JwtAuthenticationToken(jwt, extractAuthorities(jwt), AuthenticatedUser.usernameOf(jwt));
    }

    static Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();

        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles != null) {
            roles.stream()
                    .filter(StringUtils::hasText)
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .forEach(authorities::add);
        }

        String scp = jwt.getClaimAsString("scp");
        if (StringUtils.hasText(scp)) {
            for (String scope : scp.split(" ")) {
                if (StringUtils.hasText(scope)) {
                    authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
                }
            }
        }

        return authorities;
    }
}
