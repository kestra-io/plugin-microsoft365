package io.kestra.plugin.microsoft365.oneshare;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Download a file from OneDrive or SharePoint."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Download a file from OneDrive",
            code = """
                id: download_from_onedrive
                namespace: company.team

                tasks:
                  - id: download
                    type: io.kestra.plugin.microsoft365.oneshare.Download
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "01ABC123DEF456GHI789"
                """
        ),
        @Example(
            full = true,
            title = "Download file and process data",
            code = """
                id: download_and_process
                namespace: company.team

                tasks:
                  - id: download_file
                    type: io.kestra.plugin.microsoft365.oneshare.Download
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "01ABC123DEF456GHI789"

                  - id: read_csv
                    type: io.kestra.plugin.serdes.csv.CsvReader
                    from: "{{ outputs.download_file.uri }}"
                """
        )
    }
)
public class Download extends AbstractOneShareTask implements RunnableTask<Download.Output> {

    @Schema(
        title = "The ID of the item (file) to download."
    )
    @NotNull
    private Property<String> itemId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        GraphServiceClient client = this.graphClient(runContext);
        String rDriveId = runContext.render(this.driveId).as(String.class).orElseThrow();
        String rItemId = runContext.render(this.itemId).as(String.class).orElseThrow();

        // Validate inputs
        if (rItemId == null || rItemId.trim().isEmpty()) {
            throw new IllegalArgumentException("Item ID cannot be empty");
        }

        runContext.logger().info("Downloading item '{}' from drive '{}'", rItemId, rDriveId);

        File tempFile = null;
        
        try {
            // Get the file content stream
            InputStream inputStream;
            try {
                inputStream = client.drives().byDriveId(rDriveId).items().byDriveItemId(rItemId).content().get();
            } catch (ApiException e) {
                if (e.getResponseStatusCode() == 404) {
                    throw new IllegalArgumentException(
                        String.format("Item '%s' not found in drive '%s'. The file may not exist or the ID is incorrect", 
                            rItemId, rDriveId), e);
                } else if (e.getResponseStatusCode() == 403) {
                    throw new IllegalStateException(
                        String.format("Permission denied. Insufficient permissions to download item '%s' from drive '%s'", 
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
                } else if (e.getResponseStatusCode() == 416) {
                    throw new IllegalStateException(
                        String.format("Invalid range request for item '%s'. The requested byte range is not satisfiable", rItemId), e);
                }

                throw new RuntimeException(
                    String.format("Failed to download item '%s' from drive '%s': %s", 
                        rItemId, rDriveId, e.getMessage()), e);
            }

            if (inputStream == null) {
                throw new IllegalStateException(
                    String.format("Failed to download item '%s': No content stream received from Microsoft Graph API", rItemId));
            }

            // Create temp file and write content using try-with-resources and InputStream.transferTo
            try {
                tempFile = runContext.workingDir().createTempFile().toFile();
                try (InputStream in = inputStream; FileOutputStream out = new FileOutputStream(tempFile)) {
                    long totalBytesRead = in.transferTo(out);
                    runContext.logger().debug("Downloaded {} bytes for item '{}'", totalBytesRead, rItemId);
                }
            } catch (IOException e) {
                throw new RuntimeException(
                    String.format("Failed to write downloaded content to temporary file for item '%s': %s", 
                        rItemId, e.getMessage()), e);
            }

            // Store file in Kestra storage
            URI uri;
            try {
                uri = runContext.storage().putFile(tempFile);
            } catch (IOException e) {
                throw new RuntimeException(
                    String.format("Failed to store downloaded file in Kestra storage for item '%s': %s", 
                        rItemId, e.getMessage()), e);
            }
            
            runContext.logger().info("Successfully downloaded item '{}' to storage", rItemId);
            return Output.builder().uri(uri).build();
            
        } catch (ApiException e) {
            // Handle any uncaught ApiException
            runContext.logger().error("Microsoft Graph API error while downloading item: {}", e.getMessage(), e);
            throw new RuntimeException(
                String.format("Failed to download item '%s' from drive '%s': %s", 
                    rItemId, rDriveId, e.getMessage()), e);
                    
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Re-throw our custom exceptions
            throw e;
        } catch (Exception e) {
            runContext.logger().error("Unexpected error while downloading item: {}", e.getMessage(), e);
            throw new RuntimeException(
                String.format("Unexpected error while downloading item '%s' from drive '%s': %s", 
                    rItemId, rDriveId, e.getMessage()), e);
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The URI of the downloaded file in Kestra's internal storage."
        )
        private final URI uri;
    }
}
