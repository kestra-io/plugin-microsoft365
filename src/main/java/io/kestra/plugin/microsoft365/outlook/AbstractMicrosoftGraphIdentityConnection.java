package io.kestra.plugin.microsoft365.outlook;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.Optional;
import io.kestra.core.models.annotations.PluginProperty;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractMicrosoftGraphIdentityConnection extends Task {
    @Schema(title = "Azure tenant ID", description = "Entra tenant (directory) ID used for Graph auth")
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> tenantId;

    @Schema(title = "Azure client ID", description = "Application (client) ID of the Graph app registration")
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> clientId;

    @Schema(title = "Azure client secret", description = "Client secret for the app registration; required for client-credentials flow")
    @NotNull
    @PluginProperty(group = "main", secret = true)
    protected Property<String> clientSecret;

    @Schema(title = "User principal name", description = "Mailbox UPN/email to act on; defaults to app context when omitted")
    @PluginProperty(group = "advanced")
    protected Property<String> userPrincipalName;

    @Schema(title = "Scopes", description = "Space-separated Graph scopes; default uses `.default` application permissions")
    @Builder.Default
    @PluginProperty(group = "advanced")
    protected Property<String> scopes = Property.ofValue("https://graph.microsoft.com/.default");

    protected GraphServiceClient createGraphClient(RunContext runContext) throws Exception {
        String rTenantId = runContext.render(tenantId).as(String.class).orElseThrow();
        String rClientId = runContext.render(clientId).as(String.class).orElseThrow();
        String rClientSecret = runContext.render(clientSecret).as(String.class).orElseThrow();

        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
            .tenantId(rTenantId)
            .clientId(rClientId)
            .clientSecret(rClientSecret)
            .build();

        return new GraphServiceClient(credential);
    }

    protected Optional<String> getUserPrincipalName(RunContext runContext) throws Exception {
        if (userPrincipalName == null) return Optional.empty();
        return runContext.render(userPrincipalName).as(String.class);
    }
}
