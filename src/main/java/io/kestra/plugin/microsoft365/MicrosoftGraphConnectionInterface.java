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
    @PluginProperty(secret = true, group = "advanced")
    Property<String> getPemCertificate();

    @Schema(title = "Tenant ID")
    @PluginProperty(group = "connection")
    Property<String> getTenantId();

    @Schema(
            title = "Username",
            description = """
                    Username of the delegated user to authenticate as (Resource Owner Password Credentials flow).
                    Set this together with password for delegated (acting-as-user) authentication instead of app-only \
                    clientSecret/pemCertificate credentials. Required for operations Microsoft Graph rejects under \
                    app-only auth, such as sending Teams channel or chat messages.
                    """
    )
    @PluginProperty(group = "connection")
    Property<String> getUsername();

    @Schema(
            title = "Password",
            description = """
                    Password of the delegated user, used together with username for Resource Owner Password \
                    Credentials (ROPC) authentication.
                    """
    )
    @PluginProperty(secret = true, group = "connection")
    Property<String> getPassword();
}
