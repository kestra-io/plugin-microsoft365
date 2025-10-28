package io.kestra.plugin.microsoft365.oneshare;

import com.microsoft.graph.drives.item.items.item.children.ChildrenRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.microsoft365.oneshare.models.OneShareFile;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List files in a OneDrive or SharePoint folder."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "List files in OneDrive folder",
            code = """
                id: list_onedrive_files
                namespace: company.team

                tasks:
                  - id: list_files
                    type: io.kestra.plugin.microsoft365.oneshare.ListFiles
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "root"
                """
        ),
        @Example(
            full = true,
            title = "List files in specific folder",
            code = """
                id: list_folder_contents
                namespace: company.team

                tasks:
                  - id: list_folder_files
                    type: io.kestra.plugin.microsoft365.oneshare.ListFiles
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "01FOLDER123456789"
                """
        )
    }
)
public class ListFiles extends AbstractOneShareTask implements RunnableTask<ListFiles.Output> {

    @Schema(
        title = "The ID of the item (folder) to list children from. If not provided, the root of the drive is used."
    )
    private Property<String> itemId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        GraphServiceClient client = this.graphClient(runContext);
        String rDriveId = runContext.render(this.driveId).as(String.class).orElseThrow();
        String rItemId = runContext.render(this.itemId).as(String.class).orElse(null);

        runContext.logger().info("Listing files in drive '{}' from item '{}'", rDriveId, rItemId);

        // Fetch first page
        DriveItemCollectionResponse result = client.drives()
            .byDriveId(rDriveId)
            .items()
            .byDriveItemId(Objects.requireNonNullElse(rItemId, "root"))
            .children()
            .get();

        // Collect all items from all pages
        List<DriveItem> allItems = new ArrayList<>();
        if (result.getValue() != null) {
            allItems.addAll(result.getValue());
        }

        // Paginate through all remaining pages
        String nextLink = result.getOdataNextLink();
        while (nextLink != null) {
            runContext.logger().debug("Fetching next page: {}", nextLink);
            DriveItemCollectionResponse nextPage = fetchNextPage(client, nextLink);
            if (nextPage.getValue() != null) {
                allItems.addAll(nextPage.getValue());
            }
            nextLink = nextPage.getOdataNextLink();
        }

        runContext.logger().info("Retrieved {} total items", allItems.size());

        List<OneShareFile> files = allItems.stream()
            .map(OneShareFile::of)
            .collect(Collectors.toList());

        return Output.builder().files(files).build();
    }

    /**
     * Fetch the next page of results using the @odata.nextLink URL
     */
    private DriveItemCollectionResponse fetchNextPage(GraphServiceClient client, String nextLink) throws Exception {
        // Use the ChildrenRequestBuilder with the nextLink URL
        ChildrenRequestBuilder builder = new ChildrenRequestBuilder(nextLink, client.getRequestAdapter());
        return builder.get();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The list of files."
        )
        private final List<OneShareFile> files;
    }
}
