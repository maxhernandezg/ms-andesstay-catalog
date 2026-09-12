package cl.andesstay.catalog.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentacion OpenAPI del catalogo, con el esquema Bearer para pegar el token de Azure AD.
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME_NAME = "bearer-jwt";

    @Bean
    public OpenAPI catalogOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AndesStay - ms-andesstay-catalog")
                        .version("1.0.0")
                        .description("Catalogo de unidades alojables y disponibilidad. "
                                + "Todos los endpoints /api/** exigen un JWT de Azure AD (Entra ID).")
                        .contact(new Contact().name("Equipo AndesStay - DSY1107")))
                .components(new Components().addSecuritySchemes(SCHEME_NAME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME));
    }
}
