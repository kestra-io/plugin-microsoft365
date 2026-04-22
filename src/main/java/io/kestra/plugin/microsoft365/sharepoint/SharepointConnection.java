package io.kestra.plugin.microsoft365.sharepoint;


import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;

import java.util.Objects;
import io.kestra.core.models.annotations.PluginProperty;

@Getter
@Builder
public class SharepointConnection {

    @Schema(
        title = "Azure tenant ID",
        description = "Azure AD (Entra ID) tenant GUID used for Graph authentication"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> tenantId;

    @Schema(
        title = "Azure client ID",
        description = "Application (client) ID of the registered Entra ID app"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> clientId;

    @Schema(
        title = "Azure client secret",
        description = "Client secret for the app registration; required for client-credentials flow"
    )
    @NotNull
    @PluginProperty(group = "main", secret = true)
    private Property<String> clientSecret;

    @Schema(
        title = "SharePoint site ID",
        description = "Site identifier in Graph format `hostname,siteId,webId`"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> siteId;

    @Schema(
        title = "Drive ID",
        description = "Document library ID; if omitted the first drive returned for the site is used"
    )
    @PluginProperty(group = "advanced")
    private Property<String> driveId;

    public GraphServiceClient createClient(RunContext runContext) throws Exception{
        String rTenantId = runContext.render(tenantId).as(String.class).orElseThrow();
        String rClientId = runContext.render(clientId).as(String.class).orElseThrow();
        String rClientSecret = runContext.render(clientSecret).as(String.class).orElseThrow();
        ClientSecretCredential clientSecretCredential = new ClientSecretCredentialBuilder()
            .clientId(rClientId)
            .clientSecret(rClientSecret)
            .tenantId(rTenantId)
            .build();

        final String[] scopes = new String[] { "https://graph.microsoft.com/.default" };

        return new GraphServiceClient(clientSecretCredential, scopes);
    }

    public String getDriveId(RunContext runContext, GraphServiceClient client) throws Exception {
        if (driveId != null){
            return runContext.render(driveId).as(String.class).orElseThrow();
        }

        var drive = client.sites().bySiteId(getSiteId(runContext)).drives().get();
        return Objects.requireNonNull(drive.getValue()).get(0).getId();
    }

    public String getSiteId(RunContext runContext) throws Exception {
        return runContext.render(siteId).as(String.class).orElseThrow();
    }
}
