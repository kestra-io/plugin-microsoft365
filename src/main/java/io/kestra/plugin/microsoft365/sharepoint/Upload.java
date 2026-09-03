package io.kestra.plugin.microsoft365.sharepoint;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.microsoft.graph.core.models.UploadResult;
import com.microsoft.graph.core.tasks.LargeFileUploadTask;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.UploadSession;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.microsoft365.GraphResumableUpload;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import io.kestra.core.models.annotations.PluginProperty;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Upload file to SharePoint",
    description = """
        Uploads a file from Kestra internal storage to a SharePoint document library. Files up to \
        `largeFileThreshold` (4MB by default) are uploaded with a single Microsoft Graph PUT (simple upload); above that \
        threshold the file is uploaded through a resumable upload session in chunks of `chunkSize` bytes. \
        `conflictBehavior` is honored for resumable uploads but ignored for simple uploads, which always overwrite an \
        existing file. Requires Microsoft Graph permissions Files.ReadWrite.All and Sites.ReadWrite.All."""
)
@Plugin(
    examples = {
        @Example(
            title = "Upload a file to SharePoint root",
            full = true,
            code = """
                id: microsoft365_sharepoint_upload
                namespace: company.team

                tasks:
                  - id: upload
                    type: io.kestra.plugin.microsoft365.sharepoint.Upload
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    from: "{{ outputs.previous_task.uri }}"
                    to: "report.pdf"
                    parentId: "root"
                """
        ),
        @Example(
            title = "Upload a file to a specific folder with replace conflict behavior",
            full = true,
            code = """
                id: microsoft365_sharepoint_upload_folder
                namespace: company.team

                tasks:
                  - id: upload
                    type: io.kestra.plugin.microsoft365.sharepoint.Upload
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    from: "kestra:///data/output.xlsx"
                    to: "monthly-report.xlsx"
                    parentId: "01BYE5RZ6QN3ZWBTURF3F43DSUNZYRZD5Q"
                    conflictBehavior: REPLACE
                """
        ),
        @Example(
            title = "Upload a large file using a resumable upload session with a custom chunk size",
            full = true,
            code = """
                id: microsoft365_sharepoint_upload_large_file
                namespace: company.team

                tasks:
                  - id: upload
                    type: io.kestra.plugin.microsoft365.sharepoint.Upload
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    from: "{{ outputs.previous_task.uri }}"
                    to: "archive.zip"
                    parentId: "root"
                    conflictBehavior: REPLACE
                    chunkSize: 10485760 # 10MB, must be a multiple of 320 KiB
                """
        )
    }
)
public class Upload extends AbstractSharepointTask implements RunnableTask<Upload.Output> {

    @Schema(
        title = "Source file URI",
        description = "URI of the file in Kestra internal storage to upload"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> from;

    @Schema(
        title = "Destination filename",
        description = "Filename to create in SharePoint"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> to;

    @Schema(
        title = "Parent folder ID",
        description = "Parent folder ID; use 'root' for the document library root"
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<String> parentId = Property.ofValue("root");

    @Schema(
        title = "Conflict behavior",
        description = """
            How to handle an existing file at the destination when uploading through a resumable upload \
            session (files larger than `largeFileThreshold`): FAIL aborts the upload, REPLACE overwrites the \
            existing file, and RENAME creates a new file instead. Ignored for simple uploads (files at or below \
            `largeFileThreshold`), which always overwrite an existing file."""
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<ConflictBehavior> conflictBehavior = Property.ofValue(ConflictBehavior.FAIL);

    @Schema(
        title = "Large file threshold",
        description = """
            Size, in bytes, above which the file is uploaded through a Microsoft Graph resumable upload session \
            instead of a single PUT. Defaults to 4MB (4,194,304 bytes). Simple upload itself supports files up to \
            250MB, so raising this trades chunked resumability for fewer round trips."""
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Long> largeFileThreshold = Property.ofValue(DEFAULT_LARGE_FILE_THRESHOLD);

    @Schema(
        title = "Chunk size for large files",
        description = """
            Size, in bytes, of each chunk sent when uploading a file larger than `largeFileThreshold` through a \
            Microsoft Graph resumable upload session; files at or below it use simple upload and are not chunked. \
            Must be a positive multiple of 320 KiB (327,680 bytes) and strictly less than 60 MiB \
            (62,914,560 bytes), as required by the Graph API; an invalid value fails the task whatever the \
            file size. Microsoft recommends a value between 5 and 10 MiB. Defaults to 5MB (5,242,880 bytes)."""
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Long> chunkSize = Property.ofValue(DEFAULT_CHUNK_SIZE);

    private static final long DEFAULT_CHUNK_SIZE = 5L * 1024 * 1024; // 5MB
    // Simple upload itself supports files up to 250MB; 4MB is the default point at which this plugin switches to a
    // resumable transfer, matching the default of oneshare.Upload's largeFileThreshold.
    private static final long DEFAULT_LARGE_FILE_THRESHOLD = 4L * 1024 * 1024;
    private static final long CHUNK_SIZE_ALIGNMENT = 320L * 1024; // Graph requires resumable upload byte ranges aligned to 320 KiB
    private static final long MAX_CHUNK_SIZE = 60L * 1024 * 1024; // 60MiB, exclusive: Graph requires each request to be "less than 60 MiB"

    // Runtime state, not a plugin property: holds the upload session this task instance created so kill() can free it.
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Getter(AccessLevel.NONE)
    @JsonIgnore
    private final GraphResumableUpload resumableUpload = new GraphResumableUpload();

    @Override
    public void kill() {
        resumableUpload.kill();
    }

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rTo = runContext.render(to).as(String.class).orElseThrow();
        String rParentId = runContext.render(parentId).as(String.class).orElse("root");
        URI fromUri = new URI(runContext.render(from).as(String.class).orElseThrow());
        long rChunkSize = runContext.render(chunkSize).as(Long.class).orElse(DEFAULT_CHUNK_SIZE);
        long rLargeFileThreshold = runContext.render(largeFileThreshold).as(Long.class).orElse(DEFAULT_LARGE_FILE_THRESHOLD);
        ConflictBehavior rConflictBehavior = runContext.render(conflictBehavior).as(ConflictBehavior.class).orElse(ConflictBehavior.FAIL);

        if (rChunkSize <= 0 || rChunkSize % CHUNK_SIZE_ALIGNMENT != 0) {
            throw new IllegalArgumentException(
                "Invalid chunkSize (%d bytes): the Microsoft Graph API requires a positive multiple of 320 KiB (327,680 bytes) for resumable uploads".formatted(rChunkSize));
        }
        if (rChunkSize >= MAX_CHUNK_SIZE) {
            throw new IllegalArgumentException(
                "Invalid chunkSize (%d bytes): the Microsoft Graph API requires each resumable upload request to be less than 60 MiB (62,914,560 bytes), so the largest valid value is 62,586,880 bytes".formatted(rChunkSize));
        }

        SharepointConnection connection = this.connection(runContext);
        GraphServiceClient client = connection.createClient(runContext);
        String driveId = connection.getDriveId(runContext, client);

        String itemPath = rParentId + ":/" + rTo + ":";
        long fileSize = runContext.storage().getAttributes(fromUri).getSize();

        DriveItem uploadedItem;
        if (fileSize <= rLargeFileThreshold) {
            runContext.logger().debug("Uploading '{}' ({} bytes) with a Graph simple upload", rTo, fileSize);

            try (InputStream fileStream = runContext.storage().getFile(fromUri)) {
                uploadedItem = client.drives().byDriveId(driveId)
                    .items().byDriveItemId(itemPath)
                    .content()
                    .put(fileStream);
            }
        } else {
            runContext.logger().debug("Uploading '{}' ({} bytes) with a Graph resumable upload session in {} bytes chunks", rTo, fileSize, rChunkSize);

            var uploadSession = createUploadSession(client, driveId, itemPath, rConflictBehavior);
            if (uploadSession == null || uploadSession.getUploadUrl() == null) {
                throw new IllegalStateException(
                    "Failed to create a Microsoft Graph upload session for '" + rTo + "': no upload URL was returned. " +
                        "Verify the Files.ReadWrite.All / Sites.ReadWrite.All permissions and that parent folder '" + rParentId + "' exists");
            }

            try (InputStream fileStream = runContext.storage().getFile(fromUri)) {
                uploadedItem = uploadInChunks(runContext, uploadSession, fileStream, fileSize, rChunkSize);
            }
        }

        return Output.builder()
            .itemId(uploadedItem.getId())
            .name(uploadedItem.getName())
            .webUrl(uploadedItem.getWebUrl())
            .size(uploadedItem.getSize())
            .build();
    }

    protected UploadSession createUploadSession(GraphServiceClient client, String driveId, String itemPath, ConflictBehavior conflictBehavior) {
        return GraphResumableUpload.createSession(client, driveId, itemPath, conflictBehavior.getValue());
    }

    protected DriveItem uploadInChunks(RunContext runContext, UploadSession uploadSession, InputStream fileStream, long fileSize, long chunkSize) throws Exception {
        return resumableUpload.upload(runContext, uploadSession, fileStream, fileSize, chunkSize, this::performUpload);
    }

    protected UploadResult<DriveItem> performUpload(LargeFileUploadTask<DriveItem> task) throws IOException, InterruptedException {
        return task.upload();
    }

    @Getter
    public enum ConflictBehavior {
        FAIL("fail"),
        REPLACE("replace"),
        RENAME("rename");

        private final String value;

        ConflictBehavior(String value) {
            this.value = value;
        }

    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "ID of the uploaded item"
        )
        private String itemId;

        @Schema(
            title = "Name of the uploaded file"
        )
        private String name;

        @Schema(
            title = "Web URL of the uploaded file"
        )
        private String webUrl;

        @Schema(
            title = "Size of the uploaded file in bytes"
        )
        private Long size;
    }
}
