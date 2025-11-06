package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.Folder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

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
public class Create extends AbstractSharepointTask implements RunnableTask<Create.Output> {

    @Schema(
            title = "Parent folder ID",
            description = "The ID of the parent folder where the item will be created. Use 'root' for the root of the document library."
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
            title = "Item type",
            description = "Whether to create a FILE or FOLDER"
    )
    @NotNull
    @Builder.Default
    private Property<ItemType> itemType = Property.ofValue(ItemType.FILE);

    @Schema(
        title = "The content of the file.",
        description = "The content to be written to the new file. If not provided, an empty folder will be created."
    )
    private Property<String> content;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String rParentId = runContext.render(parentId).as(String.class).orElseThrow();
        String rName = runContext.render(name).as(String.class).orElseThrow();
        String rContent = runContext.render(content).as(String.class).orElse(null);

        ItemType rItemType = runContext.render(itemType).as(ItemType.class).orElse(ItemType.FILE);

        SharepointConnection connection = this.connection(runContext);
        GraphServiceClient client = connection.createClient(runContext);
        String driveId = connection.getDriveId(runContext, client);
        String siteId = connection.getSiteId(runContext);

        DriveItem createdItem;

        if (rItemType == ItemType.FOLDER) {
            createdItem = createFolder(client, driveId, rParentId, rName, logger);
        } else {
            createdItem = createFile(client, driveId, rParentId, rName, rContent, logger);
        }

        return Output.builder()
            .itemId(createdItem.getId())
            .itemName(createdItem.getName())
            .webUrl(createdItem.getWebUrl())
            .build();
    }

    private DriveItem createFolder(GraphServiceClient client, String driveId, String parentId, String folderName, Logger logger) {
        DriveItem newFolder = new DriveItem();
        newFolder.setName(folderName);
        newFolder.setFolder(new Folder());

        DriveItem createdItem = client.drives().byDriveId(driveId)
            .items().byDriveItemId(parentId)
            .children()
            .post(newFolder);
        logger.info("Created folder '{}' in parent '{}'", folderName, parentId);
        
        return createdItem;
    }

    private DriveItem createFile(GraphServiceClient client, String driveId, String parentId, String fileName, String content, Logger logger) {
        // Create a file using simple upload
        // For small files, use PUT to /drives/{drive-id}/items/{parent-id}:/{filename}:/content
        byte[] contentBytes = (content != null) ? content.getBytes() : new byte[0];

        java.io.ByteArrayInputStream inputStream = new java.io.ByteArrayInputStream(contentBytes);

        DriveItem createdItem = client.drives().byDriveId(driveId)
            .items().byDriveItemId(parentId + ":/" + fileName + ":")
            .content()
            .put(inputStream);
        logger.info("Created file '{}' in parent '{}'", fileName, parentId);
        
        return createdItem;
    }

    public enum ItemType {
        FILE,
        FOLDER
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
    }
}
