package io.kestra.plugin.microsoft365.oneshare;

import com.microsoft.graph.drives.item.items.item.children.ChildrenRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
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
import java.util.Objects;
import java.util.stream.Collectors;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List files in a OneDrive or SharePoint folder.",
    description = "Required Microsoft Graph application permissions: Files.Read.All and Sites.Read.All."
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
                    type: io.kestra.plugin.microsoft365.oneshare.List
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
                    type: io.kestra.plugin.microsoft365.oneshare.List
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "01FOLDER123456789"
                """
        )
    }
)
public class List extends AbstractOneShareTask implements RunnableTask<List.Output> {

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

        try {
            // Fetch first page
            DriveItemCollectionResponse result;
            try {
                result = client.drives()
                    .byDriveId(rDriveId)
                    .items()
                    .byDriveItemId(Objects.requireNonNullElse(rItemId, "root"))
                    .children()
                    .get();
            } catch (ApiException e) {
                if (e.getResponseStatusCode() == 404) {
                    throw new IllegalArgumentException(
                        String.format("Folder '%s' not found in drive '%s'. The folder may not exist or the ID is incorrect", 
                            rItemId != null ? rItemId : "root", rDriveId), e);
                } else if (e.getResponseStatusCode() == 403) {
                    throw new IllegalStateException(
                        String.format("Permission denied. Insufficient permissions to list files in drive '%s'", 
                            rDriveId), e);
                } else if (e.getResponseStatusCode() == 401) {
                    throw new IllegalStateException(
                        "Authentication failed. Please verify your credentials (tenantId, clientId, clientSecret)", e);
                } else if (e.getResponseStatusCode() == 400) {
                    throw new IllegalArgumentException(
                        String.format("Invalid request. Item '%s' may not be a folder or the request is malformed", 
                            rItemId != null ? rItemId : "root"), e);
                } else if (e.getResponseStatusCode() == 429) {
                    throw new IllegalStateException(
                        "Rate limit exceeded. Too many requests to Microsoft Graph API. Please retry after some time", e);
                } else if (e.getResponseStatusCode() == 503 || e.getResponseStatusCode() == 504) {
                    throw new IllegalStateException(
                        "Microsoft Graph API is temporarily unavailable. Please retry after some time", e);
                }
                
                throw new RuntimeException(
                    String.format("Failed to list files in drive '%s' from item '%s': %s", 
                        rDriveId, rItemId != null ? rItemId : "root", e.getMessage()), e);
            }

            if (result == null) {
                throw new IllegalStateException(
                    String.format("Failed to list files: No response received from Microsoft Graph API for drive '%s'", rDriveId));
            }

            // Collect all items from all pages
            java.util.List<DriveItem> allItems = new ArrayList<>();
            if (result.getValue() != null) {
                allItems.addAll(result.getValue());
            }

            // Paginate through all remaining pages
            String nextLink = result.getOdataNextLink();
            int pageCount = 1;
            
            while (nextLink != null) {
                runContext.logger().debug("Fetching page {} from nextLink", pageCount + 1);
                
                DriveItemCollectionResponse nextPage;
                try {
                    nextPage = fetchNextPage(client, nextLink);
                } catch (ApiException e) {
                    runContext.logger().warn("Failed to fetch page {}: {}. Returning partial results", 
                        pageCount + 1, e.getMessage());
                    
                    if (e.getResponseStatusCode() == 429) {
                        throw new IllegalStateException(
                            String.format("Rate limit exceeded while fetching page %d. Retrieved %d items so far", 
                                pageCount + 1, allItems.size()), e);
                    } else if (e.getResponseStatusCode() == 503 || e.getResponseStatusCode() == 504) {
                        throw new IllegalStateException(
                            String.format("Microsoft Graph API became unavailable while fetching page %d. Retrieved %d items so far", 
                                pageCount + 1, allItems.size()), e);
                    }
                    
                    // For other errors during pagination, log and break (return partial results)
                    runContext.logger().error("Pagination error at page {}: {}. Returning {} items retrieved so far", 
                        pageCount + 1, e.getMessage(), allItems.size());
                    break;
                } catch (Exception e) {
                    runContext.logger().error("Unexpected error during pagination at page {}: {}. Returning {} items retrieved so far", 
                        pageCount + 1, e.getMessage(), allItems.size());
                    break;
                }
                
                if (nextPage != null && nextPage.getValue() != null) {
                    allItems.addAll(nextPage.getValue());
                    nextLink = nextPage.getOdataNextLink();
                    pageCount++;
                } else {
                    break;
                }
            }

            runContext.logger().info("Retrieved {} total items across {} pages", allItems.size(), pageCount);

            java.util.List<OneShareFile> files = allItems.stream()
                .map(OneShareFile::of)
                .collect(Collectors.toList());

            return Output.builder()
                .files(files)
                .count(files.size())
                .build();
                
        } catch (ApiException e) {
            // Handle any uncaught ApiException
            runContext.logger().error("Microsoft Graph API error while listing files: {}", e.getMessage(), e);
            throw new RuntimeException(
                String.format("Failed to list files in drive '%s': %s", rDriveId, e.getMessage()), e);
                
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Re-throw our custom exceptions
            throw e;
        } catch (Exception e) {
            runContext.logger().error("Unexpected error while listing files: {}", e.getMessage(), e);
            throw new RuntimeException(
                String.format("Unexpected error while listing files in drive '%s': %s", 
                    rDriveId, e.getMessage()), e);
        }
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
        private final java.util.List<OneShareFile> files;

        @Schema(
            title = "The number of files."
        )
        private final int count;
    }
}
