package io.kestra.plugin.microsoft365.oneshare;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.File;
import com.microsoft.graph.models.Folder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.microsoft365.oneshare.models.ItemType;
import io.kestra.plugin.microsoft365.oneshare.models.OneShareFile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Create a file or folder in OneDrive or SharePoint.",
    description = "Required Microsoft Graph application permissions: Files.ReadWrite.All and Sites.ReadWrite.All."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Create a folder in OneDrive",
            code = """
                id: create_onedrive_folder
                namespace: company.team

                tasks:
                  - id: create
                    type: io.kestra.plugin.microsoft365.oneshare.Create
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    parentId: "root"
                    name: "new-folder"
                    itemType: FOLDER
                """
        ),
        @Example(
            full = true,
            title = "Create a text file with content",
            code = """
                id: create_text_file
                namespace: company.team

                tasks:
                  - id: create_file
                    type: io.kestra.plugin.microsoft365.oneshare.Create
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    parentId: "root"
                    name: "readme.txt"
                    content: "Hello World!"
                """
        )
    }
)
public class Create extends AbstractOneShareTask implements RunnableTask<Create.Output> {

    @Schema(
        title = "The ID of the parent folder. If not provided, the root of the drive is used."
    )
    private Property<String> parentId;

    @Schema(
        title = "The name of the file or folder to create."
    )
    @NotNull
    private Property<String> name;

    @Schema(
        title = "Type of item to create.",
        description = "Specify whether to create a FILE or FOLDER. Defaults to FILE."
    )
    @Builder.Default
    private Property<ItemType> itemType = Property.ofValue(ItemType.FILE);

    @Schema(
        title = "Content of the file to create."
    )
    private Property<String> content;

    @Override
    public Output run(RunContext runContext) throws Exception {
        GraphServiceClient client = this.graphClient(runContext);
        String rDriveId = runContext.render(this.driveId).as(String.class).orElseThrow();
        String rParentId = runContext.render(this.parentId).as(String.class).orElse("root");
        String rName = runContext.render(this.name).as(String.class).orElseThrow();
        ItemType rItemType = runContext.render(this.itemType).as(ItemType.class).orElse(ItemType.FILE);
        String rContent = runContext.render(this.content).as(String.class).orElse(null);

        // Validate inputs
        if (rName == null || rName.trim().isEmpty()) {
            throw new IllegalArgumentException("File or folder name cannot be empty");
        }

        // Validate name doesn't contain invalid characters for OneDrive/SharePoint
        if (rName.matches(".*[<>:\"/\\\\|?*].*")) {
            throw new IllegalArgumentException("File or folder name contains invalid characters. " +
                "OneDrive/SharePoint names cannot contain: < > : \" / \\ | ? *");
        }

        runContext.logger().info("Creating a {} in drive '{}' with name '{}'", rItemType == ItemType.FOLDER ? "folder" : "file", rDriveId, rName);

        try {
            DriveItem result;
            if (rItemType == ItemType.FOLDER) {
                // Create a folder using POST /children with folder facet
                DriveItem driveItem = new DriveItem();
                driveItem.setName(rName);
                driveItem.setFolder(new Folder());
                
                try {
                    result = client.drives().byDriveId(rDriveId).items().byDriveItemId(rParentId).children().post(driveItem);
                } catch (ApiException e) {
                    if (e.getResponseStatusCode() == 404) {
                        throw new IllegalArgumentException(
                            String.format("Parent folder '%s' not found in drive '%s'. Please verify the parent ID exists.", 
                                rParentId, rDriveId), e);
                    } else if (e.getResponseStatusCode() == 409) {
                        throw new IllegalStateException(
                            String.format("A folder named '%s' already exists in the parent folder '%s'", 
                                rName, rParentId), e);
                    } else if (e.getResponseStatusCode() == 403) {
                        throw new IllegalStateException(
                            String.format("Permission denied. Insufficient permissions to create folder in drive '%s'", 
                                rDriveId), e);
                    }
                    throw new RuntimeException(
                        String.format("Failed to create folder '%s' in drive '%s': %s", 
                            rName, rDriveId, e.getMessage()), e);
                }
            } else {
                // For files with content, use PUT content endpoint (recommended approach)
                if (rContent != null && !rContent.isEmpty()) {
                    byte[] bytes = rContent.getBytes(StandardCharsets.UTF_8);
                    String itemPath = rParentId + ":/" + rName + ":";
                    
                    try {
                        result = client.drives().byDriveId(rDriveId).items().byDriveItemId(itemPath).content().put(new ByteArrayInputStream(bytes));
                    } catch (ApiException e) {
                        if (e.getResponseStatusCode() == 404) {
                            throw new IllegalArgumentException(
                                String.format("Parent folder '%s' not found in drive '%s'. Please verify the parent ID exists.", 
                                    rParentId, rDriveId), e);
                        } else if (e.getResponseStatusCode() == 403) {
                            throw new IllegalStateException(
                                String.format("Permission denied. Insufficient permissions to create file in drive '%s'", 
                                    rDriveId), e);
                        } else if (e.getResponseStatusCode() == 507) {
                            throw new IllegalStateException(
                                String.format("Insufficient storage. Drive '%s' does not have enough space", 
                                    rDriveId), e);
                        }
                        throw new RuntimeException(
                            String.format("Failed to create file '%s' with content in drive '%s': %s", 
                                rName, rDriveId, e.getMessage()), e);
                    }
                } else {
                    // For empty files, create using POST /children with file facet
                    DriveItem driveItem = new DriveItem();
                    driveItem.setName(rName);
                    driveItem.setFile(new File());
                    
                    try {
                        result = client.drives().byDriveId(rDriveId).items().byDriveItemId(rParentId).children().post(driveItem);
                    } catch (ApiException e) {
                        if (e.getResponseStatusCode() == 404) {
                            throw new IllegalArgumentException(
                                String.format("Parent folder '%s' not found in drive '%s'. Please verify the parent ID exists.", 
                                    rParentId, rDriveId), e);
                        } else if (e.getResponseStatusCode() == 409) {
                            throw new IllegalStateException(
                                String.format("A file named '%s' already exists in the parent folder '%s'", 
                                    rName, rParentId), e);
                        } else if (e.getResponseStatusCode() == 403) {
                            throw new IllegalStateException(
                                String.format("Permission denied. Insufficient permissions to create file in drive '%s'", 
                                    rDriveId), e);
                        }
                        throw new RuntimeException(
                            String.format("Failed to create empty file '%s' in drive '%s': %s", 
                                rName, rDriveId, e.getMessage()), e);
                    }
                }
            }

            if (result == null) {
                throw new IllegalStateException(
                    String.format("Failed to create %s '%s': No response received from Microsoft Graph API", 
                        rItemType == ItemType.FOLDER ? "folder" : "file", rName));
            }

            runContext.logger().info("Successfully created {} '{}' with ID: {}", 
                rItemType == ItemType.FOLDER ? "folder" : "file", rName, result.getId());

            return Output.builder().file(OneShareFile.of(result)).build();
            
        } catch (ApiException e) {
            // Handle any uncaught ApiException
            runContext.logger().error("Microsoft Graph API error while creating {}: {}", 
                rItemType == ItemType.FOLDER ? "folder" : "file", e.getMessage(), e);
            
            if (e.getResponseStatusCode() == 401) {
                throw new IllegalStateException(
                    "Authentication failed. Please verify your credentials (tenantId, clientId, clientSecret)", e);
            } else if (e.getResponseStatusCode() == 429) {
                throw new IllegalStateException(
                    "Rate limit exceeded. Too many requests to Microsoft Graph API. Please retry after some time", e);
            } else if (e.getResponseStatusCode() == 503 || e.getResponseStatusCode() == 504) {
                throw new IllegalStateException(
                    "Microsoft Graph API is temporarily unavailable. Please retry after some time", e);
            }
            
            throw new RuntimeException(
                String.format("Failed to create %s '%s' in drive '%s': %s", 
                    rItemType == ItemType.FOLDER ? "folder" : "file", rName, rDriveId, e.getMessage()), e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Re-throw our custom exceptions
            throw e;
        } catch (Exception e) {
            runContext.logger().error("Unexpected error while creating {}: {}", 
                rItemType == ItemType.FOLDER ? "folder" : "file", e.getMessage(), e);
            throw new RuntimeException(
                String.format("Unexpected error while creating %s '%s' in drive '%s': %s", 
                    rItemType == ItemType.FOLDER ? "folder" : "file", rName, rDriveId, e.getMessage()), e);
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The created file or folder metadata."
        )
        private final OneShareFile file;
    }
}
