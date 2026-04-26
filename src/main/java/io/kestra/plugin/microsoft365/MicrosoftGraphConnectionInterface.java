package io.kestra.plugin.microsoft365;

import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;
import io.kestra.core.models.annotations.PluginProperty;

public interface MicrosoftGraphConnectionInterface {
    @Schema(
            title = "Client ID",
            description = """
                    Client ID of the Azure service principal.
                    If you don't have a service principal, refer to [create a service principal with Azure CLI](https://learn.microsoft.com/en-us/cli/azure/azure-cli-sp-tutorial-1?tabs=bash).
                    """
    )
    @PluginProperty(group = "connection")
    Property<String> getClientId();

    @Schema(
            title = "Client Secret",
            description = """
                    Service principal client secret.
                    Use this for Client Secret authentication. Provide clientId, tenantId, and clientSecret.
                    Either clientSecret OR pemCertificate must be provided, not both.
                    """
    )
    @PluginProperty(secret = true, group = "connection")
    Property<String> getClientSecret();

    @Schema(
            title = "PEM Certificate",
            description = """
                Alternative authentication method using certificate-based authentication.
                Use this for Client Certificate authentication. Provide clientId, tenantId, and pemCertificate.
                Either clientSecret OR pemCertificate must be provided, not both.
            """
    )
    @PluginProperty(group = "advanced")
    Property<String> getPemCertificate();

    @Schema(title = "Tenant ID")
    @PluginProperty(group = "connection")
    Property<String> getTenantId();
}
