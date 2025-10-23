package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.microsoft365.AbstractMicrosoftGraphIdentityConnection;
import io.kestra.plugin.microsoft365.sharepoint.models.Item;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.util.ArrayList;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "List items in a SharePoint document library",
            full = true,
            code = """
                id: microsoft365_sharepoint_list
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.microsoft365.sharepoint.List
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                """
        ),
        @Example(
            title = "List items in a specific folder",
            full = true,
            code = """
                id: microsoft365_sharepoint_list_folder
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.microsoft365.sharepoint.List
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    parentId: "01BYE5RZ6QN3ZWBTURF3F43DSUNZYRZD5Q"
                """
        )
    },
    metrics = {
        @Metric(
            name = "count",
            type = Counter.TYPE,
            unit = "count",
            description = "Number of items returned"
        )
    }
)
@Schema(
    title = "List items in a SharePoint document library or folder."
)
public class List extends AbstractMicrosoftGraphIdentityConnection implements RunnableTask<List.Output> {
    
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
        description = "The unique identifier of the parent folder. If not specified, lists items in the root of the drive."
    )
    private Property<String> parentId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        
        String rSiteId = runContext.render(siteId).as(String.class).orElseThrow();
        String rDriveId = runContext.render(driveId).as(String.class).orElseThrow();
        String rParentId = parentId != null ? runContext.render(parentId).as(String.class).orElse(null) : null;

        GraphServiceClient graphClient = createGraphClient(runContext);

        logger.debug("Listing items in SharePoint site '{}', drive '{}'{}", 
            rSiteId, rDriveId, rParentId != null ? ", parent '" + rParentId + "'" : "");

        java.util.List<Item> items = new ArrayList<>();
        
        // List items - either from root or from specific parent
        if (rParentId != null) {
            // List children of a specific item
            DriveItemCollectionResponse response = graphClient.drives().byDriveId(rDriveId)
                .items().byDriveItemId(rParentId)
                .children()
                .get();

            while (response != null) {
                if (response.getValue() != null) {
                    for (DriveItem driveItem : response.getValue()) {
                        items.add(Item.of(driveItem));
                    }
                }
                
                // Handle pagination
                String nextLink = response.getOdataNextLink();
                if (nextLink != null) {
                    response = graphClient.drives().byDriveId(rDriveId)
                        .items().byDriveItemId(rParentId)
                        .children()
                        .get();
                } else {
                    response = null;
                }
            }
        } else {
            // List items in the root of the drive
            DriveItemCollectionResponse response = graphClient.drives().byDriveId(rDriveId)
                .root()
                .children()
                .get();

            while (response != null) {
                if (response.getValue() != null) {
                    for (DriveItem driveItem : response.getValue()) {
                        items.add(Item.of(driveItem));
                    }
                }
                
                // Handle pagination
                String nextLink = response.getOdataNextLink();
                if (nextLink != null) {
                    response = graphClient.drives().byDriveId(rDriveId)
                        .root()
                        .children()
                        .get();
                } else {
                    response = null;
                }
            }
        }

        runContext.metric(Counter.of("count", items.size()));
        logger.info("Found {} items", items.size());

        return Output.builder()
            .items(items)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The list of items.",
            description = "List of files and folders in the SharePoint location."
        )
        private final java.util.List<Item> items;
    }
}
