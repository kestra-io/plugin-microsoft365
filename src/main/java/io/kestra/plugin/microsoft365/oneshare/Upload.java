package io.kestra.plugin.microsoft365.oneshare;

import com.microsoft.graph.core.models.IProgressCallback;
import com.microsoft.graph.core.models.UploadResult;
import com.microsoft.graph.core.tasks.LargeFileUploadTask;
import com.microsoft.graph.drives.item.items.item.createuploadsession.CreateUploadSessionPostRequestBody;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemUploadableProperties;
import com.microsoft.graph.models.UploadSession;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.microsoft365.oneshare.models.OneShareFile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Upload a file to OneDrive or SharePoint.",
    description = "Supports both small and large file uploads. Files larger than the threshold (default 4MB) will use resumable upload sessions for reliability and progress tracking."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Upload a FILE input to OneDrive",
            code = """
                id: upload_to_onedrive
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE

                tasks:
                  - id: upload
                    type: io.kestra.plugin.microsoft365.oneshare.Upload
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    parentId: "root"
                    fileName: "uploaded-file.csv"
                    from: "{{ inputs.file }}"
                """
        ),
        @Example(
            full = true,
            title = "Download data and upload to OneDrive",
            code = """
                id: download_and_upload
                namespace: company.team

                tasks:
                  - id: download
                    type: io.kestra.plugin.core.http.Download
                    uri: https://example.com/data.csv

                  - id: upload
                    type: io.kestra.plugin.microsoft365.oneshare.Upload
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    parentId: "root"
                    fileName: "data.csv"
                    from: "{{ outputs.download.uri }}"
                """
        )
    }
)
public class Upload extends AbstractOneShareTask implements RunnableTask<Upload.Output> {

    /**
     * File size threshold (in bytes) to determine whether to use simple or resumable upload.
     * Default is 4MB (4 * 1024 * 1024 bytes).
     * Microsoft recommends using upload sessions for files larger than 4MB.
     */
    private static final long DEFAULT_LARGE_FILE_THRESHOLD = 4 * 1024 * 1024L; // 4MB

    /**
     * Default slice size for chunked uploads.
     * Must be a multiple of 320 KiB (327,680 bytes).
     * Default is 3.2 MB (10 * 327,680 bytes = 3,276,800 bytes).
     */
    private static final int DEFAULT_MAX_SLICE_SIZE = 10 * 327680; // 3.2 MB - exactly 10 chunks of 320 KiB

    /**
     * Default maximum number of retry attempts for upload operations.
     */
    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 5;

    @Schema(
        title = "The ID of the parent folder.",
        description = "The ID of the parent folder. If not provided, the root of the drive is used."
    )
    @Builder.Default
    private Property<String> parentId = Property.ofValue("root");

    @Schema(
        title = "The name of the file to upload.",
        description = "The desired filename in OneDrive/SharePoint. Can be different from the source filename."
    )
    @NotNull
    private Property<String> fileName;

    @Schema(
        title = "The file from Kestra's internal storage to upload.",
        description = "URI of the file in Kestra's internal storage. Can be from inputs, outputs, or other tasks."
    )
    @NotNull
    @PluginProperty(internalStorageURI = true)
    private Property<String> from;

    @Schema(
        title = "File size threshold for using resumable upload.",
        description = "Files larger than this threshold (in bytes) will use resumable upload sessions. " +
                     "Microsoft recommends 4MB (4194304 bytes) as the threshold. Default: 4MB"
    )
    @Builder.Default
    private Property<Long> largeFileThreshold = Property.ofValue(DEFAULT_LARGE_FILE_THRESHOLD);

    @Schema(
        title = "Maximum slice size for chunked uploads.",
        description = "The size of each chunk when uploading large files (in bytes). " +
                     "Must be a multiple of 320 KiB (327,680 bytes). Default: 3.2 MB (3,276,800 bytes)"
    )
    @Builder.Default
    private Property<Integer> maxSliceSize = Property.ofValue(DEFAULT_MAX_SLICE_SIZE);

    @Schema(
        title = "Maximum number of retry attempts.",
        description = "The maximum number of attempts to retry failed upload operations. Default: 5"
    )
    @Builder.Default
    private Property<Integer> maxRetryAttempts = Property.ofValue(DEFAULT_MAX_RETRY_ATTEMPTS);

    @Schema(
        title = "Conflict behavior when file exists.",
        description = "Defines how to handle conflicts when a file with the same name already exists. Default: REPLACE"
    )
    @Builder.Default
    private Property<ConflictBehavior> conflictBehavior = Property.ofValue(ConflictBehavior.REPLACE);

    public enum ConflictBehavior {
        @Schema(description = "Replace the existing file")
        REPLACE,
        @Schema(description = "Fail if file exists")
        FAIL,
        @Schema(description = "Rename the new file")
        RENAME
    }

    @Override
    public Output run(RunContext runContext) throws Exception {
        GraphServiceClient client = this.graphClient(runContext);
        Logger logger = runContext.logger();

        // Render all properties
        String rDriveId = runContext.render(this.driveId).as(String.class).orElseThrow();
        String rParentId = runContext.render(this.parentId).as(String.class).orElse("root");
        String rFileName = runContext.render(this.fileName).as(String.class).orElseThrow();
        URI rFrom = new URI(runContext.render(this.from).as(String.class).orElseThrow());
        long rLargeFileThreshold = runContext.render(this.largeFileThreshold).as(Long.class).orElse(DEFAULT_LARGE_FILE_THRESHOLD);
        int rMaxSliceSize = runContext.render(this.maxSliceSize).as(Integer.class).orElse(DEFAULT_MAX_SLICE_SIZE);
        int rMaxRetryAttempts = runContext.render(this.maxRetryAttempts).as(Integer.class).orElse(DEFAULT_MAX_RETRY_ATTEMPTS);
        ConflictBehavior rConflictBehavior = runContext.render(this.conflictBehavior).as(ConflictBehavior.class).orElse(ConflictBehavior.REPLACE);

        // Validate inputs
        if (rFileName == null || rFileName.trim().isEmpty()) {
            throw new IllegalArgumentException("File name cannot be empty");
        }

        // Validate name doesn't contain invalid characters for OneDrive/SharePoint
        if (rFileName.matches(".*[<>:\"/\\\\|?*].*")) {
            throw new IllegalArgumentException("File name contains invalid characters. " +
                "OneDrive/SharePoint names cannot contain: < > : \" / \\ | ? *");
        }

        // Validate slice size is multiple of 320 KiB
        if (rMaxSliceSize % 327680 != 0) {
            throw new IllegalArgumentException(
                String.format("Max slice size (%d bytes) must be a multiple of 320 KiB (327,680 bytes)", rMaxSliceSize));
        }

        if (rMaxRetryAttempts < 1) {
            throw new IllegalArgumentException("Max retry attempts must be at least 1");
        }

        logger.info("Uploading file '{}' to drive '{}' as '{}'", rFrom, rDriveId, rFileName);

        // Create a temporary file from the input stream to determine size
        File tempFile = null;
        long fileSize = 0;

        try {
            tempFile = File.createTempFile("kestra-upload-", ".tmp");
            
            // Copy input stream to temp file to determine size
            try (InputStream inputStream = runContext.storage().getFile(rFrom);
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    fileSize += bytesRead;
                }
            } catch (IOException e) {
                throw new RuntimeException(
                    String.format("Failed to read file from storage '%s': %s", rFrom, e.getMessage()), e);
            }

            if (fileSize == 0) {
                logger.warn("File size is 0 bytes. Uploading empty file");
            }

            logger.debug("File size: {} bytes, threshold: {} bytes", fileSize, rLargeFileThreshold);

            DriveItem result;

            // Choose upload method based on file size
            if (fileSize <= rLargeFileThreshold) {
                // Simple upload for small files
                logger.info("Using simple upload for file of size {} bytes", fileSize);
                result = simpleUpload(client, tempFile, rDriveId, rParentId, rFileName, rConflictBehavior, logger);
                runContext.metric(Counter.of("file.size", fileSize));
            } else {
                // Resumable upload for large files
                logger.info("Using resumable upload for file of size {} bytes", fileSize);
                result = resumableUpload(client, tempFile, fileSize, rDriveId, rParentId, rFileName,
                                       rMaxSliceSize, rMaxRetryAttempts, rConflictBehavior, runContext, logger);
            }

            if (result == null) {
                throw new IllegalStateException(
                    String.format("Failed to upload file '%s': No response received from Microsoft Graph API", rFileName));
            }

            logger.info("File uploaded successfully. ID: {}, Size: {} bytes", result.getId(), result.getSize());

            return Output.builder()
                    .file(OneShareFile.of(result))
                    .build();
                    
        } catch (ApiException e) {
            logger.error("Microsoft Graph API error during upload: {}", e.getMessage(), e);
            
            if (e.getResponseStatusCode() == 401) {
                throw new IllegalStateException(
                    "Authentication failed. Please verify your credentials (tenantId, clientId, clientSecret)", e);
            } else if (e.getResponseStatusCode() == 403) {
                throw new IllegalStateException(
                    String.format("Permission denied. Insufficient permissions to upload to drive '%s'", rDriveId), e);
            } else if (e.getResponseStatusCode() == 404) {
                throw new IllegalArgumentException(
                    String.format("Parent folder '%s' not found in drive '%s'", rParentId, rDriveId), e);
            } else if (e.getResponseStatusCode() == 429) {
                throw new IllegalStateException(
                    "Rate limit exceeded. Too many requests to Microsoft Graph API. Please retry after some time", e);
            } else if (e.getResponseStatusCode() == 503 || e.getResponseStatusCode() == 504) {
                throw new IllegalStateException(
                    "Microsoft Graph API is temporarily unavailable. Please retry after some time", e);
            } else if (e.getResponseStatusCode() == 507) {
                throw new IllegalStateException(
                    String.format("Insufficient storage. Drive '%s' does not have enough space for file '%s' (%d bytes)", 
                        rDriveId, rFileName, fileSize), e);
            }
            
            throw new RuntimeException(
                String.format("Failed to upload file '%s' to drive '%s': %s", 
                    rFileName, rDriveId, e.getMessage()), e);
                    
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Re-throw our custom exceptions
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error during upload: {}", e.getMessage(), e);
            throw new RuntimeException(
                String.format("Unexpected error while uploading file '%s' to drive '%s': %s", 
                    rFileName, rDriveId, e.getMessage()), e);
        } finally {
            // Clean up temp file
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile.toPath());
                } catch (Exception e) {
                    logger.warn("Failed to delete temporary file: {}", tempFile.getAbsolutePath(), e);
                }
            }
        }
    }

    /**
     * Simple upload for files smaller than the threshold.
     * Uses a single PUT request to upload the entire file.
     */
    private DriveItem simpleUpload(GraphServiceClient client, File file, String driveId,
                                   String parentId, String fileName, ConflictBehavior conflictBehavior,
                                   Logger logger) throws Exception {
        try (InputStream fileStream = Files.newInputStream(file.toPath())) {
            String itemPath = buildItemPath(parentId, fileName);

            // For simple upload, conflict behavior is handled via query parameters
            // However, the SDK doesn't directly support this in content().put()
            // So we use the default behavior (replace)
            if (conflictBehavior == ConflictBehavior.FAIL) {
                logger.warn("FAIL conflict behavior is not fully supported for simple uploads. " +
                           "File will be replaced if it exists.");
            }

            try {
                return client.drives()
                        .byDriveId(driveId)
                        .items()
                        .byDriveItemId(itemPath)
                        .content()
                        .put(fileStream);
            } catch (ApiException e) {
                if (e.getResponseStatusCode() == 404) {
                    throw new IllegalArgumentException(
                        String.format("Parent folder '%s' not found in drive '%s'", parentId, driveId), e);
                } else if (e.getResponseStatusCode() == 403) {
                    throw new IllegalStateException(
                        String.format("Permission denied. Insufficient permissions to upload to drive '%s'", driveId), e);
                } else if (e.getResponseStatusCode() == 507) {
                    throw new IllegalStateException(
                        String.format("Insufficient storage. Drive '%s' does not have enough space", driveId), e);
                } else if (e.getResponseStatusCode() == 413) {
                    throw new IllegalStateException(
                        String.format("File '%s' is too large for simple upload. Consider increasing largeFileThreshold", fileName), e);
                }
                throw new RuntimeException(
                    String.format("Simple upload failed for file '%s': %s", fileName, e.getMessage()), e);
            }
        } catch (IOException e) {
            throw new RuntimeException(
                String.format("Failed to read file '%s' for upload: %s", fileName, e.getMessage()), e);
        }
    }

    /**
     * Resumable upload for large files.
     * Creates an upload session and uploads the file in chunks with progress tracking and retry logic.
     */
    private DriveItem resumableUpload(GraphServiceClient client, File file, long fileSize,
                                     String driveId, String parentId, String fileName,
                                     int maxSliceSize, int maxAttempts, ConflictBehavior conflictBehavior,
                                     RunContext runContext, Logger logger) throws Exception {
        // Build the item path
        String itemPath = buildItemPath(parentId, fileName);

        // Create upload session request
        CreateUploadSessionPostRequestBody uploadSessionRequest = new CreateUploadSessionPostRequestBody();
        DriveItemUploadableProperties properties = new DriveItemUploadableProperties();

        // Set conflict behavior
        String conflictBehaviorValue = switch (conflictBehavior) {
            case REPLACE -> "replace";
            case FAIL -> "fail";
            case RENAME -> "rename";
        };
        properties.getAdditionalData().put("@microsoft.graph.conflictBehavior", conflictBehaviorValue);
        uploadSessionRequest.setItem(properties);

        logger.debug("Creating upload session for item: {}", itemPath);

        // Create upload session
        UploadSession uploadSession;
        try {
            uploadSession = client.drives()
                    .byDriveId(driveId)
                    .items()
                    .byDriveItemId(itemPath)
                    .createUploadSession()
                    .post(uploadSessionRequest);
        } catch (ApiException e) {
            logger.error("Failed to create upload session", e);
            
            if (e.getResponseStatusCode() == 404) {
                throw new IllegalArgumentException(
                    String.format("Parent folder '%s' not found in drive '%s'", parentId, driveId), e);
            } else if (e.getResponseStatusCode() == 403) {
                throw new IllegalStateException(
                    String.format("Permission denied. Insufficient permissions to create upload session in drive '%s'", driveId), e);
            } else if (e.getResponseStatusCode() == 409 && conflictBehavior == ConflictBehavior.FAIL) {
                throw new IllegalStateException(
                    String.format("File '%s' already exists and conflict behavior is set to FAIL", fileName), e);
            } else if (e.getResponseStatusCode() == 507) {
                throw new IllegalStateException(
                    String.format("Insufficient storage. Drive '%s' does not have enough space for file '%s' (%d bytes)", 
                        driveId, fileName, fileSize), e);
            }
            
            throw new RuntimeException(
                String.format("Failed to create upload session for file '%s': %s", fileName, e.getMessage()), e);
        } catch (Exception e) {
            logger.error("Unexpected error creating upload session", e);
            throw new RuntimeException(
                String.format("Unexpected error creating upload session for file '%s': %s", fileName, e.getMessage()), e);
        }

        if (uploadSession == null || uploadSession.getUploadUrl() == null) {
            throw new IllegalStateException(
                String.format("Failed to create upload session for file '%s': no upload URL returned", fileName));
        }

        logger.info("Upload session created. Upload URL: {}", uploadSession.getUploadUrl());

        // Create the large file upload task using SDK
        try (InputStream fileStream = new FileInputStream(file)) {
            LargeFileUploadTask<DriveItem> largeFileUploadTask;
            try {
                largeFileUploadTask = new LargeFileUploadTask<>(
                        client.getRequestAdapter(),
                        uploadSession,
                        fileStream,
                        fileSize,
                        maxSliceSize,
                        DriveItem::createFromDiscriminatorValue
                );
            } catch (Exception e) {
                logger.error("Failed to create large file upload task", e);
                throw new RuntimeException(
                    String.format("Failed to create large file upload task for file '%s': %s", fileName, e.getMessage()), e);
            }

            // Create progress callback
            IProgressCallback callback = (current, max) -> {
                double percentage = (current * 100.0) / max;
                logger.info("Upload progress: {} / {} bytes ({} %)",
                           current, max, String.format("%.2f", percentage));

                // Report progress metrics
                try {
                    runContext.metric(Counter.of("upload.progress.bytes", current));
                    runContext.metric(Counter.of("upload.progress.percentage", (long) percentage));
                } catch (Exception e) {
                    logger.warn("Failed to report progress metric", e);
                }
            };

            // Perform the upload
            logger.info("Starting chunked upload with max slice size: {} bytes, max attempts: {}",
                       maxSliceSize, maxAttempts);

            UploadResult<DriveItem> uploadResult;
            try {
                uploadResult = largeFileUploadTask.upload(maxAttempts, callback);
            } catch (ApiException e) {
                logger.error("Upload failed with API exception", e);
                
                if (e.getResponseStatusCode() == 507) {
                    throw new IllegalStateException(
                        String.format("Insufficient storage during upload. Drive '%s' ran out of space", driveId), e);
                } else if (e.getResponseStatusCode() == 416) {
                    throw new IllegalStateException(
                        String.format("Invalid byte range during upload for file '%s'. Upload session may have expired", fileName), e);
                } else if (e.getResponseStatusCode() == 404) {
                    throw new IllegalStateException(
                        String.format("Upload session not found or expired for file '%s'. Please retry the upload", fileName), e);
                }
                
                throw new RuntimeException(
                    String.format("Upload failed for file '%s' after %d attempts: %s", 
                        fileName, maxAttempts, e.getMessage()), e);
            } catch (Exception e) {
                logger.error("Error during upload", e);
                throw new RuntimeException(
                    String.format("Error during upload of file '%s': %s", fileName, e.getMessage()), e);
            }

            if (uploadResult == null) {
                throw new IllegalStateException(
                    String.format("Upload failed for file '%s': No upload result returned", fileName));
            }

            if (uploadResult.isUploadSuccessful()) {
                logger.info("Upload completed successfully");
                runContext.metric(Counter.of("file.size", fileSize));
                
                if (uploadResult.itemResponse == null) {
                    throw new IllegalStateException(
                        String.format("Upload succeeded but no item response returned for file '%s'", fileName));
                }
                
                return uploadResult.itemResponse;
            } else {
                throw new RuntimeException(
                    String.format("Upload failed for file '%s': Upload was not successful after %d attempts", 
                        fileName, maxAttempts));
            }
        } catch (IOException e) {
            throw new RuntimeException(
                String.format("Failed to read file '%s' during upload: %s", fileName, e.getMessage()), e);
        }
    }

    /**
     * Builds the item path for the Graph API request.
     * Format: "parentId:/fileName:" or "root:/fileName:"
     */
    private String buildItemPath(String parentId, String fileName) {
        return parentId + ":/" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20") + ":";
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The uploaded file metadata."
        )
        private final OneShareFile file;
    }
}