package io.kestra.plugin.microsoft365.oneshare;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.VoidOutput;
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
@Schema(
    title = "Delete a file or folder from OneDrive or SharePoint.",
    description = "Required Microsoft Graph application permissions: Files.ReadWrite.All and Sites.ReadWrite.All."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Delete a file from OneDrive",
            code = """
                id: delete_onedrive_file
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.microsoft365.oneshare.Delete
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "01ABC123DEF456GHI789"
                """
        ),
        @Example(
            full = true,
            title = "List and delete specific files",
            code = """
                id: cleanup_old_files
                namespace: company.team

                tasks:
                  - id: list_files
                    type: io.kestra.plugin.microsoft365.oneshare.List
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "01TEMP_FOLDER"

                  - id: delete_file
                    type: io.kestra.plugin.microsoft365.oneshare.Delete
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "{{ outputs.list_files.files[0].id }}"
                """
        )
    }
)
public class Delete extends AbstractOneShareTask implements RunnableTask<VoidOutput> {

    @Schema(
        title = "The ID of the item (file or folder) to delete."
    )
    @NotNull
    private Property<String> itemId;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        GraphServiceClient client = this.graphClient(runContext);
        String rDriveId = runContext.render(this.driveId).as(String.class).orElseThrow();
        String rItemId = runContext.render(this.itemId).as(String.class).orElseThrow();

        // Validate inputs
        if (rItemId == null || rItemId.trim().isEmpty()) {
            throw new IllegalArgumentException("Item ID cannot be empty");
        }

        runContext.logger().info("Deleting item '{}' from drive '{}'", rItemId, rDriveId);

        try {
            client.drives().byDriveId(rDriveId).items().byDriveItemId(rItemId).delete();
            runContext.logger().info("Successfully deleted item '{}'", rItemId);
            
        } catch (ApiException e) {
            runContext.logger().error("Microsoft Graph API error while deleting item: {}", e.getMessage(), e);
            
            if (e.getResponseStatusCode() == 404) {
                throw new IllegalArgumentException(
                    String.format("Item '%s' not found in drive '%s'. It may have already been deleted or the ID is incorrect", 
                        rItemId, rDriveId), e);
            } else if (e.getResponseStatusCode() == 403) {
                throw new IllegalStateException(
                    String.format("Permission denied. Insufficient permissions to delete item '%s' from drive '%s'", 
                        rItemId, rDriveId), e);
            } else if (e.getResponseStatusCode() == 401) {
                throw new IllegalStateException(
                    "Authentication failed. Please verify your credentials (tenantId, clientId, clientSecret)", e);
            } else if (e.getResponseStatusCode() == 429) {
                throw new IllegalStateException(
                    "Rate limit exceeded. Too many requests to Microsoft Graph API. Please retry after some time", e);
            } else if (e.getResponseStatusCode() == 503 || e.getResponseStatusCode() == 504) {
                throw new IllegalStateException(
                    "Microsoft Graph API is temporarily unavailable. Please retry after some time", e);
            } else if (e.getResponseStatusCode() == 423) {
                throw new IllegalStateException(
                    String.format("Item '%s' is locked and cannot be deleted at this time", rItemId), e);
            }
            
            throw new RuntimeException(
                String.format("Failed to delete item '%s' from drive '%s': %s", 
                    rItemId, rDriveId, e.getMessage()), e);
                    
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Re-throw our custom exceptions
            throw e;
        } catch (Exception e) {
            runContext.logger().error("Unexpected error while deleting item: {}", e.getMessage(), e);
            throw new RuntimeException(
                String.format("Unexpected error while deleting item '%s' from drive '%s': %s", 
                    rItemId, rDriveId, e.getMessage()), e);
        }

        return null;
    }
}
