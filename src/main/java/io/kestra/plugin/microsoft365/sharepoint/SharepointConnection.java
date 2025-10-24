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

@Getter
@Builder
public class SharepointConnection {

    @Schema(
        title = "Azure Tenant ID",
        description = "The Azure Active Directory tenant ID"
    )
    @NotNull
    private Property<String> tenantId;

    @Schema(
        title = "Azure Client ID",
        description = "The client ID registered in Azure"
    )
    @NotNull
    private Property<String> clientId;

    @Schema(
        title = "Azure Client Secret",
        description = "The client secret for the registered application"
    )
    @NotNull
    private Property<String> clientSecret;

    @Schema(
        title = "Sharepoint Site ID",
        description = "The id of the"
    )
    @NotNull
    private Property<String> siteId;

    @Schema(
        title = "Drive ID",
        description = "The id of the document library within the SharePoint site. If  not provided, the default document library will be used."
    )
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
