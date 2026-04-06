package io.kestra.plugin.microsoft365.sharepoint;

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
import io.kestra.core.models.annotations.PluginProperty;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractSharepointTask extends Task {

    @Schema(
        title = "Azure tenant ID",
        description = "Azure AD (Entra ID) tenant GUID used for Graph authentication"
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> tenantId;

    @Schema(
        title = "Azure client ID",
        description = "Application (client) ID of the registered Entra ID app"
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> clientId;

    @Schema(
        title = "Azure client secret",
        description = "Client secret for the app registration; required for client-credentials flow"
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> clientSecret;

    @Schema(
        title = "SharePoint site ID",
        description = "Site identifier in Graph format `hostname,siteId,webId`"
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> siteId;

    @Schema(
        title = "Drive ID",
        description = "Document library ID; if omitted the first drive returned for the site is used"
    )
    @PluginProperty(group = "advanced")
    protected Property<String> driveId;

    protected SharepointConnection connection(RunContext runContext) throws Exception {

        return SharepointConnection.builder()
            .tenantId(tenantId)
            .clientId(clientId)
            .clientSecret(clientSecret)
            .siteId(siteId)
            .driveId(driveId)
            .build();
    }
}
