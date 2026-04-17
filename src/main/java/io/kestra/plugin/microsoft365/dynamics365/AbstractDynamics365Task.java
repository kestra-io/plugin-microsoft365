package io.kestra.plugin.microsoft365.dynamics365;

import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ClientCertificateCredentialBuilder;
import com.azure.identity.ClientSecretCredentialBuilder;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractDynamics365Task extends Task {

    @Schema(
        title = "Tenant ID",
        description = "Azure AD (Entra ID) tenant GUID used for authentication."
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> tenantId;

    @Schema(
        title = "Client ID",
        description = """
            Client ID of the Azure service principal.
            If you don't have a service principal, refer to \
            [create a service principal with Azure CLI](https://learn.microsoft.com/en-us/cli/azure/azure-cli-sp-tutorial-1?tabs=bash).
            """
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> clientId;

    @Schema(
        title = "Client Secret",
        description = """
            Service principal client secret.
            Use this for Client Secret authentication. Provide clientId, tenantId, and clientSecret.
            Either clientSecret OR pemCertificate must be provided, not both.
            """
    )
    @PluginProperty(group = "connection")
    protected Property<String> clientSecret;

    @Schema(
        title = "PEM Certificate",
        description = """
            Alternative authentication method using certificate-based authentication.
            Use this for Client Certificate authentication. Provide clientId, tenantId, and pemCertificate.
            Either clientSecret OR pemCertificate must be provided, not both.
            """
    )
    @PluginProperty(group = "advanced")
    protected Property<String> pemCertificate;

    /**
     * Acquires a Bearer access token for the given OAuth2 scope using the configured credentials.
     * A fresh token is obtained on every call — no caching between runs.
     */
    protected String getAccessToken(RunContext runContext, String scope) throws IllegalVariableEvaluationException {
        var rTenantId = runContext.render(this.tenantId).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("tenantId is required for authentication"));
        var rClientId = runContext.render(this.clientId).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("clientId is required for authentication"));

        var rClientSecret = runContext.render(this.clientSecret).as(String.class).orElse(null);
        var rPemCertificate = runContext.render(this.pemCertificate).as(String.class).orElse(null);

        if (StringUtils.isNotBlank(rClientSecret) && StringUtils.isNotBlank(rPemCertificate)) {
            throw new IllegalArgumentException("Only one of clientSecret or pemCertificate may be provided, not both");
        }

        var tokenContext = new TokenRequestContext().addScopes(scope);

        if (StringUtils.isNotBlank(rClientSecret)) {
            runContext.logger().debug("Authentication is using Client Secret Credentials");
            return new ClientSecretCredentialBuilder()
                .clientId(rClientId)
                .tenantId(rTenantId)
                .clientSecret(rClientSecret)
                .build()
                .getToken(tokenContext).block().getToken();
        }

        if (StringUtils.isNotBlank(rPemCertificate)) {
            runContext.logger().debug("Authentication is using Client Certificate Credentials");
            return new ClientCertificateCredentialBuilder()
                .clientId(rClientId)
                .tenantId(rTenantId)
                .pemCertificate(new ByteArrayInputStream(rPemCertificate.getBytes(StandardCharsets.UTF_8)))
                .build()
                .getToken(tokenContext).block().getToken();
        }

        throw new IllegalArgumentException("Either clientSecret or pemCertificate must be provided for authentication");
    }

    /**
     * Returns a default {@link HttpConfiguration} with a 30-second connect timeout and 60-second read idle timeout.
     */
    protected HttpConfiguration httpConfiguration() {
        return HttpConfiguration.builder()
            .timeout(TimeoutConfiguration.builder()
                .connectTimeout(Property.ofValue(Duration.ofSeconds(30)))
                .readIdleTimeout(Property.ofValue(Duration.ofSeconds(60)))
                .build())
            .build();
    }

    /**
     * Extracts the HTTP error body from an {@link HttpClientResponseException}.
     * The body may be {@code byte[]} or {@code String} depending on which interceptor fires.
     */
    protected static String responseBodyAsString(HttpClientResponseException e) {
        var body = e.getResponse() != null ? e.getResponse().getBody() : null;
        if (body == null) return "";
        if (body instanceof String s) return s;
        if (body instanceof byte[] b) return new String(b, StandardCharsets.UTF_8);
        return body.toString();
    }
}
