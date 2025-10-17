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
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemCollectionResponse;

import java.net.URI;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "List items in a SharePoint document library.",
            code = {
                "siteId: \"your-site-id\"",
                "driveId: \"your-drive-id\"",
                "itemId: \"your-folder-id\""
            }
        )
    }
)
@Schema(
    title = "List items in a SharePoint document library or folder.",
    description = "This task allows you to list items in a SharePoint document library or folder."
)
public class List extends Task implements RunnableTask<List.Output> {
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
        description = "The unique identifier of the folder to list items from. If not provided, lists items from the root of the drive."
    )
    @PluginProperty(dynamic = true)
    private Property<String> itemId;

    @Override
    public List.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        
        String renderedSiteId = runContext.render(siteId).as(String.class).orElse(null);
        String renderedDriveId = runContext.render(driveId).as(String.class).orElse(null);
        String renderedItemId = runContext.render(itemId).as(String.class).orElse(null);
        
        logger.debug("Listing items in SharePoint site '{}', drive '{}', folder '{}'", 
            renderedSiteId, renderedDriveId, renderedItemId);
        
        try {
            com.microsoft.graph.requests.GraphServiceClient<?> graphClient = GraphClientProvider.getClient();
            
            // List items in the specified folder or drive root
            DriveItemCollectionResponse response;
            if (renderedItemId != null) {
                response = graphClient.sites(renderedSiteId)
                    .drives(renderedDriveId)
                    .items(renderedItemId)
                    .children()
                    .buildRequest()
                    .get();
            } else {
                response = graphClient.sites(renderedSiteId)
                    .drives(renderedDriveId)
                    .root()
                    .children()
                    .buildRequest()
                    .get();
            }
            
            // Convert DriveItem objects to our Item objects
            List<Item> items = new ArrayList<>();
            for (DriveItem driveItem : response.value) {
                String type = (driveItem.folder != null) ? "folder" : "file";
                Long size = (driveItem.size != null) ? driveItem.size : 0L;
                
                items.add(Item.builder()
                    .id(driveItem.id)
                    .name(driveItem.name)
                    .type(type)
                    .size(size)
                    .build());
            }
            
            return Output.builder()
                .siteId(renderedSiteId)
                .driveId(renderedDriveId)
                .itemId(renderedItemId)
                .uri(new URI("https://graph.microsoft.com/v1.0/sites/" + renderedSiteId + 
                    "/drives/" + renderedDriveId + "/items/" + (renderedItemId != null ? renderedItemId : "root") + "/children"))
                .items(items)
                .build();
        } catch (ClientException e) {
            logger.error("Failed to list items in SharePoint: {}", e.getMessage());
            throw new Exception("Failed to list items in SharePoint: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error while listing items in SharePoint: {}", e.getMessage());
            throw e;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The SharePoint site ID.",
            description = "The unique identifier of the SharePoint site."
        )
        private final String siteId;

        @Schema(
            title = "The SharePoint drive ID.",
            description = "The unique identifier of the SharePoint document library (drive)."
        )
        private final String driveId;

        @Schema(
            title = "The item ID.",
            description = "The unique identifier of the folder that was listed."
        )
        private final String itemId;

        @Schema(
            title = "The URI of the list operation.",
            description = "The Microsoft Graph API URI of the list operation."
        )
        private final URI uri;

        @Schema(
            title = "The list of items.",
            description = "The list of items in the SharePoint document library or folder."
        )
        private final List<Item> items;
    }

    @Builder
    @Getter
    public static class Item implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The ID of the item.",
            description = "The unique identifier of the item."
        )
        private final String id;

        @Schema(
            title = "The name of the item.",
            description = "The name of the item."
        )
        private final String name;

        @Schema(
            title = "The type of the item.",
            description = "The type of the item (file or folder)."
        )
        private final String type;

        @Schema(
            title = "The size of the item.",
            description = "The size of the item in bytes."
        )
        private final Long size;
    }
}
