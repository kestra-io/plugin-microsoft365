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

import com.microsoft.graph.core.ClientException;

import java.net.URI;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Delete a file or folder from a SharePoint document library.",
            code = {
                "siteId: \"your-site-id\"",
                "driveId: \"your-drive-id\"",
                "itemId: \"your-item-id\""
            }
        )
    }
)
@Schema(
    title = "Delete a file or folder from a SharePoint document library.",
    description = "This task allows you to delete a file or folder from a SharePoint document library."
)
public class Delete extends Task implements RunnableTask<Delete.Output> {
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
        title = "The item ID.",
        description = "The unique identifier of the file or folder to delete."
    )
    @PluginProperty(dynamic = true)
    private Property<String> itemId;

    @Override
    public Delete.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        
        String renderedSiteId = runContext.render(siteId).as(String.class).orElse(null);
        String renderedDriveId = runContext.render(driveId).as(String.class).orElse(null);
        String renderedItemId = runContext.render(itemId).as(String.class).orElse(null);
        
        logger.debug("Deleting item '{}' from SharePoint site '{}', drive '{}'", 
            renderedItemId, renderedSiteId, renderedDriveId);
        
        try {
            com.microsoft.graph.requests.GraphServiceClient<?> graphClient = GraphClientProvider.getClient();
            
            // Delete the item
            graphClient.sites(renderedSiteId)
                .drives(renderedDriveId)
                .items(renderedItemId)
                .buildRequest()
                .delete();
            
            return Output.builder()
                .itemId(renderedItemId)
                .uri(new URI("https://graph.microsoft.com/v1.0/sites/" + renderedSiteId + 
                    "/drives/" + renderedDriveId + "/items/" + renderedItemId))
                .deleted(true)
                .build();
        } catch (ClientException e) {
            logger.error("Failed to delete item from SharePoint: {}", e.getMessage());
            throw new Exception("Failed to delete item from SharePoint: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error while deleting item from SharePoint: {}", e.getMessage());
            throw e;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The ID of the deleted item.",
            description = "The unique identifier of the deleted file or folder."
        )
        private final String itemId;

        @Schema(
            title = "The URI of the deleted item.",
            description = "The Microsoft Graph API URI of the deleted item."
        )
        private final URI uri;

        @Schema(
            title = "Whether the item was successfully deleted.",
            description = "Indicates if the item was successfully deleted."
        )
        private final Boolean deleted;
    }
}
