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

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractSharepointTask extends Task {

    @Schema(
        title = "Azure Tenant ID",
        description = "The Azure Active Directory tenant ID"
    )
    @NotNull
    protected Property<String> tenantId;

    @Schema(
        title = "Azure Client ID",
        description = "The client ID registered in Azure"
    )
    @NotNull
    protected Property<String> clientId;

    @Schema(
        title = "Azure Client Secret",
        description = "The client secret for the registered application"
    )
    @NotNull
    protected Property<String> clientSecret;

    @Schema(
        title = "Sharepoint Site ID",
        description = "The ID of the SharePoint site."
    )
    @NotNull
    protected Property<String> siteId;

    @Schema(
        title = "Drive ID",
        description = "The ID of the document library within the SharePoint site. If not provided, the default document library will be used."
    )
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
