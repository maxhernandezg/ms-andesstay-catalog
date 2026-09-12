package cl.andesstay.catalog.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de Azure AD (Entra ID) leida desde application.yml.
 * Los valores por defecto son los placeholders literales del contrato.
 */
@ConfigurationProperties(prefix = "andesstay.security")
public class AzureAdProperties {

    /** https://login.microsoftonline.com/6f522bef-27e2-4548-89e6-c1717b61ae20/v2.0 */
    private String issuerUri = "https://login.microsoftonline.com/6f522bef-27e2-4548-89e6-c1717b61ae20/v2.0";

    /** GUID (o placeholder) de la App Registration andesstay-api. */
    private String apiClientId = "7d348e57-2f83-4648-baf0-588cbacccc3c";

    /** Scope delegado exigido en el claim scp. */
    private String requiredScope = "access_as_user";

    /** Audiencias extra aceptadas ademas de 'api://<apiClientId>' y '<apiClientId>'. */
    private List<String> extraAudiences = new ArrayList<>();

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getApiClientId() {
        return apiClientId;
    }

    public void setApiClientId(String apiClientId) {
        this.apiClientId = apiClientId;
    }

    public String getRequiredScope() {
        return requiredScope;
    }

    public void setRequiredScope(String requiredScope) {
        this.requiredScope = requiredScope;
    }

    public List<String> getExtraAudiences() {
        return extraAudiences;
    }

    public void setExtraAudiences(List<String> extraAudiences) {
        this.extraAudiences = extraAudiences;
    }

    /** Azure v1 emite el App ID URI y v2 el GUID: hay que aceptar ambos. */
    public List<String> acceptedAudiences() {
        List<String> audiences = new ArrayList<>();
        audiences.add("api://" + apiClientId);
        audiences.add(apiClientId);
        audiences.addAll(extraAudiences);
        return List.copyOf(audiences);
    }
}
