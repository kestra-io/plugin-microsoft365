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
import com.microsoft.graph.models.File;
import com.microsoft.graph.models.UploadSession;
import com.microsoft.graph.requests.GraphServiceClient;

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
            title = "Upload a file to a SharePoint document library.",
            code = {
                "siteId: \"your-site-id\"",
                "driveId: \"your-drive-id\"",
                "parentId: \"your-parent-folder-id\"",
                "filename: \"example.txt\"",
                "content: \"File content to upload\""
            }
        )
    }
)
@Schema(
    title = "Upload a file to a SharePoint document library.",
    description = "This task allows you to upload a file to a SharePoint document library. Supports simple upload for files <4MB and chunked upload for larger files."
)
public class Upload extends Task implements RunnableTask<Upload.Output> {
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
        description = "The unique identifier of the parent folder where the file will be uploaded."
    )
    @PluginProperty(dynamic = true)
    private Property<String> parentId;

    @Schema(
        title = "The filename.",
        description = "The name of the file to upload."
    )
    @PluginProperty(dynamic = true)
    private Property<String> filename;

    @Schema(
        title = "The content of the file.",
        description = "The content to be uploaded to the file."
    )
    @PluginProperty(dynamic = true)
    private Property<String> content;

    @Override
    public Upload.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        
        String renderedSiteId = runContext.render(siteId).as(String.class).orElse(null);
        String renderedDriveId = runContext.render(driveId).as(String.class).orElse(null);
        String renderedParentId = runContext.render(parentId).as(String.class).orElse(null);
        String renderedFilename = runContext.render(filename).as(String.class).orElse(null);
        String renderedContent = runContext.render(content).as(String.class).orElse(null);
        
        logger.debug("Uploading file '{}' to SharePoint site '{}', drive '{}'", 
            renderedFilename, renderedSiteId, renderedDriveId);
        
        try {
            com.microsoft.graph.requests.GraphServiceClient<?> graphClient = GraphClientProvider.getClient();
            byte[] contentBytes = renderedContent.getBytes();
            
            // For files < 4MB, use simple upload
            if (contentBytes.length < 4 * 1024 * 1024) {
                DriveItem driveItem = new DriveItem();
                driveItem.name = renderedFilename;
                driveItem.file = new File();
                driveItem.additionalDataManager().put("@microsoft.graph.conflictBehavior", 
                    new com.microsoft.graph.core.serialization.AdditionalDataHolder() {
                        @Override
                        public Map<String, Object> getAdditionalDataManager() {
                            return Collections.singletonMap("@microsoft.graph.conflictBehavior", "replace");
                        }
                    }.getAdditionalDataManager().get("@microsoft.graph.conflictBehavior"));
                
                // Create the file first
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
                    .put(contentBytes);
                
                return Output.builder()
                    .itemId(createdItem.id)
                    .itemName(createdItem.name)
                    .uri(new URI("https://graph.microsoft.com/v1.0/sites/" + renderedSiteId + 
                        "/drives/" + renderedDriveId + "/items/" + createdItem.id))
                    .uploaded(true)
                    .build();
            } else {
                // For larger files, use chunked upload
                // Create upload session
                UploadSession uploadSession = graphClient.sites(renderedSiteId)
                    .drives(renderedDriveId)
                    .items(renderedParentId)
                    .itemWithPath(renderedFilename)
                    .createUploadSession(new DriveItem())
                    .buildRequest()
                    .post();
                
                // Upload file in chunks
                com.microsoft.graph.requests.LargeFileUploadTask<DriveItem> uploadTask = 
                    new com.microsoft.graph.requests.LargeFileUploadTask<>(
                        uploadSession, 
                        graphClient, 
                        contentBytes, 
                        DriveItem.class
                    );
                
                com.microsoft.graph.requests.UploadResult<DriveItem> uploadResult = uploadTask.upload();
                
                return Output.builder()
                    .itemId(uploadResult.itemResponse.id)
                    .itemName(uploadResult.itemResponse.name)
                    .uri(new URI("https://graph.microsoft.com/v1.0/sites/" + renderedSiteId + 
                        "/drives/" + renderedDriveId + "/items/" + uploadResult.itemResponse.id))
                    .uploaded(true)
                    .build();
            }
        } catch (ClientException e) {
            logger.error("Failed to upload file to SharePoint: {}", e.getMessage());
            throw new Exception("Failed to upload file to SharePoint: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error while uploading file to SharePoint: {}", e.getMessage());
            throw e;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The ID of the uploaded item.",
            description = "The unique identifier of the uploaded file."
        )
        private final String itemId;

        @Schema(
            title = "The name of the uploaded item.",
            description = "The name of the uploaded file."
        )
        private final String itemName;

        @Schema(
            title = "The URI of the uploaded item.",
            description = "The Microsoft Graph API URI of the uploaded item."
        )
        private final URI uri;

        @Schema(
            title = "Whether the file was successfully uploaded.",
            description = "Indicates if the file was successfully uploaded."
        )
        private final Boolean uploaded;
    }
}
