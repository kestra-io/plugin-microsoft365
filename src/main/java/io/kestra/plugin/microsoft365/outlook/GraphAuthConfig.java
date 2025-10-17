package io.kestra.plugin.microsoft365.outlook;

import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotNull;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public class GraphAuthConfig {

    @Schema(
        title = "Tenant ID",
        description = "Azure AD tenant ID (directory ID) where the application is registered"
    )
    @NotNull
    private Property<String> tenantId;

    @Schema(
        title = "Client ID",
        description = "Application (client) ID from Azure AD app registration"
    )
    @NotNull
    private Property<String> clientId;

    @Schema(
        title = "Client Secret",
        description = "Client secret value from Azure AD app registration"
    )
    @NotNull
    private Property<String> clientSecret;

    @Schema(
        title = "User Principal Name",
        description = "Email address of the user for delegated authentication (optional, used for sending emails on behalf of a user)"
    )
    private Property<String> userPrincipalName;

    @Schema(
        title = "Scopes",
        description = "Microsoft Graph API scopes required for the operation"
    )
    private Property<String> scopes;
}