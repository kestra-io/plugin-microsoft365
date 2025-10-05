package io.kestra.plugin.microsoft365.sharepoint;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.File;
import com.microsoft.graph.core.ClientException;

import java.net.URI;
import java.util.Collections;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Create a new file in a SharePoint document library.",
            code = {
                "siteId: \"your-site-id\"",
                "driveId: \"your-drive-id\"",
                "parentId: \"your-parent-folder-id\"",
                "filename: \"example.txt\"",
                "content: \"File content here\""
            }
        )
    }
)
@Schema(
    title = "Create a new file or folder in a SharePoint document library.",
    description = "This task allows you to create a new file or folder in a SharePoint document library."
)
public class Create extends Task implements RunnableTask<Create.Output> {
    @Schema(
        title = "The SharePoint site ID.",
        description = "The unique identifier of the SharePoint site."
    )
    @PluginProperty(dynamic = true)
    private Property<String> siteId;

    @Schema(
        title = "The SharePoint drive ID.",
        description = "The unique identifier of the SharePoint document library (drive)."
    )
    @PluginProperty(dynamic = true)
    private Property<String> driveId;

    @Schema(
        title = "The parent item ID.",
        description = "The unique identifier of the parent folder where the new item will be created."
    )
    @PluginProperty(dynamic = true)
    private Property<String> parentId;

    @Schema(
        title = "The filename or folder name.",
        description = "The name of the file or folder to create."
    )
    @PluginProperty(dynamic = true)
    private Property<String> filename;

    @Schema(
        title = "The content of the file.",
        description = "The content to be written to the new file. If not provided, an empty folder will be created."
    )
    @PluginProperty(dynamic = true)
    private Property<String> content;

    @Override
    public Create.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        
        String renderedSiteId = runContext.render(siteId).as(String.class).orElse(null);
        String renderedDriveId = runContext.render(driveId).as(String.class).orElse(null);
        String renderedParentId = runContext.render(parentId).as(String.class).orElse(null);
        String renderedFilename = runContext.render(filename).as(String.class).orElse(null);
        String renderedContent = runContext.render(content).as(String.class).orElse(null);
        
        logger.debug("Creating file/folder '{}' in SharePoint site '{}', drive '{}'", 
            renderedFilename, renderedSiteId, renderedDriveId);
        
        try {
            com.microsoft.graph.requests.GraphServiceClient<?> graphClient = GraphClientProvider.getClient();
            
            DriveItem driveItem = new DriveItem();
            driveItem.name = renderedFilename;
            
            // If content is provided, create a file; otherwise create a folder
            if (renderedContent != null) {
                driveItem.file = new File();
                driveItem.additionalDataManager().put("@microsoft.graph.conflictBehavior", 
                    new com.microsoft.graph.core.serialization.AdditionalDataHolder() {
                        @Override
                        public Map<String, Object> getAdditionalDataManager() {
                            return Collections.singletonMap("@microsoft.graph.conflictBehavior", "replace");
                        }
                    }.getAdditionalDataManager().get("@microsoft.graph.conflictBehavior"));
                
                DriveItem createdItem = graphClient.sites(renderedSiteId)
                    .drives(renderedDriveId)
                    .items(renderedParentId)
                    .children()
                    .buildRequest()
                    .post(driveItem);
                
                // Upload content to the file
                graphClient.sites(renderedSiteId)
                    .drives(renderedDriveId)
                    .items(createdItem.id)
                    .content()
                    .buildRequest()
                    .put(renderedContent.getBytes());
                
                return Output.builder()
                    .itemId(createdItem.id)
                    .itemName(createdItem.name)
                    .uri(new URI("https://graph.microsoft.com/v1.0/sites/" + renderedSiteId + 
                        "/drives/" + renderedDriveId + "/items/" + createdItem.id))
                    .build();
            } else {
                // Create a folder
                driveItem.folder = new com.microsoft.graph.models.Folder();
                
                DriveItem createdItem = graphClient.sites(renderedSiteId)
                    .drives(renderedDriveId)
                    .items(renderedParentId)
                    .children()
                    .buildRequest()
                    .post(driveItem);
                
                return Output.builder()
                    .itemId(createdItem.id)
                    .itemName(createdItem.name)
                    .uri(new URI("https://graph.microsoft.com/v1.0/sites/" + renderedSiteId + 
                        "/drives/" + renderedDriveId + "/items/" + createdItem.id))
                    .build();
            }
        } catch (ClientException e) {
            logger.error("Failed to create item in SharePoint: {}", e.getMessage());
            throw new Exception("Failed to create item in SharePoint: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error while creating item in SharePoint: {}", e.getMessage());
            throw e;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The ID of the created item.",
            description = "The unique identifier of the created file or folder."
        )
        private final String itemId;

        @Schema(
            title = "The name of the created item.",
            description = "The name of the created file or folder."
        )
        private final String itemName;

        @Schema(
            title = "The URI of the created item.",
            description = "The Microsoft Graph API URI of the created item."
        )
        private final URI uri;
    }
}
