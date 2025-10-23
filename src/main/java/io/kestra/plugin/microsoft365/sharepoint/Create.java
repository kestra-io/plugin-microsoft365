package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.File;
import com.microsoft.graph.models.Folder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.microsoft365.AbstractMicrosoftGraphIdentityConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.net.URI;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Create a folder in SharePoint",
            full = true,
            code = """
                id: microsoft365_sharepoint_create_folder
                namespace: company.team

                tasks:
                  - id: create_folder
                    type: io.kestra.plugin.microsoft365.sharepoint.Create
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    parentId: "01BYE5RZ6QN3ZWBTURF3F43DSUNZYRZD5Q"
                    name: "NewFolder"
                """
        ),
        @Example(
            title = "Create a file with content in SharePoint",
            full = true,
            code = """
                id: microsoft365_sharepoint_create_file
                namespace: company.team

                tasks:
                  - id: create_file
                    type: io.kestra.plugin.microsoft365.sharepoint.Create
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    parentId: "01BYE5RZ6QN3ZWBTURF3F43DSUNZYRZD5Q"
                    name: "document.txt"
                    content: "Hello, SharePoint!"
                """
        )
    }
)
@Schema(
    title = "Create a file or folder in SharePoint.",
    description = "Creates a new file with optional content or an empty folder in a SharePoint document library."
)
public class Create extends AbstractMicrosoftGraphIdentityConnection implements RunnableTask<Create.Output> {
    
    @Schema(
        title = "The SharePoint site ID.",
        description = "The unique identifier of the SharePoint site."
    )
    @NotNull
    private Property<String> siteId;

    @Schema(
        title = "The SharePoint drive ID.",
        description = "The unique identifier of the SharePoint document library (drive)."
    )
    @NotNull
    private Property<String> driveId;

    @Schema(
        title = "The parent item ID.",
        description = "The unique identifier of the parent folder where the new item will be created."
    )
    @NotNull
    private Property<String> parentId;

    @Schema(
        title = "The name of the item.",
        description = "The name of the file or folder to create."
    )
    @NotNull
    private Property<String> name;

    @Schema(
        title = "The content of the file.",
        description = "The content to be written to the new file. If not provided, an empty folder will be created."
    )
    private Property<String> content;

    @Schema(
        title = "Conflict behavior.",
        description = "How to handle conflicts if an item with the same name already exists. Options: 'rename', 'replace', 'fail'."
    )
    @Builder.Default
    private Property<String> conflictBehavior = Property.ofValue("rename");

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        
        String rSiteId = runContext.render(siteId).as(String.class).orElseThrow();
        String rDriveId = runContext.render(driveId).as(String.class).orElseThrow();
        String rParentId = runContext.render(parentId).as(String.class).orElseThrow();
        String rName = runContext.render(name).as(String.class).orElseThrow();
        String rContent = content != null ? runContext.render(content).as(String.class).orElse(null) : null;
        String rConflictBehavior = runContext.render(conflictBehavior).as(String.class).orElseThrow();

        GraphServiceClient graphClient = createGraphClient(runContext);

        DriveItem driveItem;

        if (rContent != null) {
            // Create a file with content
            logger.debug("Creating file '{}' in SharePoint site '{}', drive '{}', parent '{}'", 
                rName, rSiteId, rDriveId, rParentId);

            byte[] contentBytes = rContent.getBytes();
            
            driveItem = graphClient.drives().byDriveId(rDriveId)
                .items().byDriveItemId(rParentId)
                .itemWithPath(rName)
                .content()
                .put(contentBytes);

            logger.info("Successfully created file '{}'", rName);
        } else {
            // Create a folder
            logger.debug("Creating folder '{}' in SharePoint site '{}', drive '{}', parent '{}'", 
                rName, rSiteId, rDriveId, rParentId);

            DriveItem newFolder = new DriveItem();
            newFolder.setName(rName);
            newFolder.setFolder(new Folder());
            newFolder.setAdditionalData(new java.util.HashMap<>());
            newFolder.getAdditionalData().put("@microsoft.graph.conflictBehavior", rConflictBehavior);

            driveItem = graphClient.drives().byDriveId(rDriveId)
                .items().byDriveItemId(rParentId)
                .children()
                .post(newFolder);

            logger.info("Successfully created folder '{}'", rName);
        }

        return Output.builder()
            .itemId(driveItem.getId())
            .itemName(driveItem.getName())
            .webUrl(driveItem.getWebUrl())
            .isFolder(driveItem.getFolder() != null)
            .isFile(driveItem.getFile() != null)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The ID of the created item."
        )
        private final String itemId;

        @Schema(
            title = "The name of the created item."
        )
        private final String itemName;

        @Schema(
            title = "The web URL of the created item."
        )
        private final String webUrl;

        @Schema(
            title = "Whether the created item is a folder."
        )
        private final Boolean isFolder;

        @Schema(
            title = "Whether the created item is a file."
        )
        private final Boolean isFile;
    }
}
