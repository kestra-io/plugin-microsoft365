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
    title = "Export a file from OneDrive or SharePoint to a different format."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Export file to PDF format",
            code = """
                id: export_to_pdf
                namespace: company.team

                tasks:
                  - id: export
                    type: io.kestra.plugin.microsoft365.oneshare.Export
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "01ABC123DEF456GHI789"
                    format: PDF
                """
        ),
        @Example(
            full = true,
            title = "Export file to HTML format",
            code = """
                id: export_to_html
                namespace: company.team

                tasks:
                  - id: export_html
                    type: io.kestra.plugin.microsoft365.oneshare.Export
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "01EXCEL123456789"
                    format: HTML
                """
        )
    }
)
public class Export extends AbstractOneShareTask implements RunnableTask<Export.Output> {

    public enum ExportFormat {
        @Schema(description = "Converts the item into PDF format. Supports: doc, docx, dot, dotx, dotm, dsn, dwg, eml, epub, fluidframework, form, htm, html, loop, loot, markdown, md, msg, note, odp, ods, odt, page, pps, ppsx, ppt, pptx, pulse, rtf, task, tif, tiff, wbtx, whiteboard, xls, xlsm, xlsx")
        PDF,
        
        @Schema(description = "Converts the item into HTML format. Supports: loop, fluid, wbtx")
        HTML
    }

    @Schema(
        title = "The ID of the item (file) to export."
    )
    @NotNull
    private Property<String> itemId;

    @Schema(
        title = "The format to export the file to.",
        description = "PDF format supports most file types (doc, docx, ppt, xlsx, etc.). HTML format supports loop, fluid, and wbtx files only."
    )
    @NotNull
    private Property<ExportFormat> format;

    @Override
    public Output run(RunContext runContext) throws Exception {
        GraphServiceClient client = this.graphClient(runContext);
        String rDriveId = runContext.render(this.driveId).as(String.class).orElseThrow();
        String rItemId = runContext.render(this.itemId).as(String.class).orElseThrow();
        ExportFormat rFormat = runContext.render(this.format).as(ExportFormat.class).orElseThrow();

        // Validate inputs
        if (rItemId == null || rItemId.trim().isEmpty()) {
            throw new IllegalArgumentException("Item ID cannot be empty");
        }

        runContext.logger().info("Exporting item '{}' from drive '{}' to format '{}'", rItemId, rDriveId, rFormat.name());

        File tempFile = null;
        InputStream inputStream = null;
        FileOutputStream fos = null;
        
        try {
            // Get the exported file content stream
            try {
                inputStream = client.drives().byDriveId(rDriveId).items().byDriveItemId(rItemId).content()
                    .get(requestConfiguration -> {
                        requestConfiguration.queryParameters.format = rFormat.name().toLowerCase();
                    });
            } catch (ApiException e) {
                if (e.getResponseStatusCode() == 404) {
                    throw new IllegalArgumentException(
                        String.format("Item '%s' not found in drive '%s'. The file may not exist or the ID is incorrect", 
                            rItemId, rDriveId), e);
                } else if (e.getResponseStatusCode() == 403) {
                    throw new IllegalStateException(
                        String.format("Permission denied. Insufficient permissions to export item '%s' from drive '%s'", 
                            rItemId, rDriveId), e);
                } else if (e.getResponseStatusCode() == 401) {
                    throw new IllegalStateException(
                        "Authentication failed. Please verify your credentials (tenantId, clientId, clientSecret)", e);
                } else if (e.getResponseStatusCode() == 400) {
                    throw new IllegalArgumentException(
                        String.format("Invalid export format. The file type of item '%s' cannot be exported to %s format. " +
                            "PDF supports: doc, docx, ppt, xlsx, etc. HTML supports: loop, fluid, wbtx only", 
                            rItemId, rFormat.name()), e);
                } else if (e.getResponseStatusCode() == 406) {
                    throw new IllegalArgumentException(
                        String.format("Unsupported export format '%s' for item '%s'. Please check the file type and supported export formats", 
                            rFormat.name(), rItemId), e);
                } else if (e.getResponseStatusCode() == 429) {
                    throw new IllegalStateException(
                        "Rate limit exceeded. Too many requests to Microsoft Graph API. Please retry after some time", e);
                } else if (e.getResponseStatusCode() == 503 || e.getResponseStatusCode() == 504) {
                    throw new IllegalStateException(
                        "Microsoft Graph API is temporarily unavailable. Please retry after some time", e);
                }
                
                throw new RuntimeException(
                    String.format("Failed to export item '%s' from drive '%s' to format '%s': %s", 
                        rItemId, rDriveId, rFormat.name(), e.getMessage()), e);
            }
            
            if (inputStream == null) {
                throw new IllegalStateException(
                    String.format("Failed to export item '%s': No content stream received from Microsoft Graph API", rItemId));
            }
            
            // Create temp file and write content
            try {
                tempFile = runContext.workingDir().createTempFile().toFile();
                fos = new FileOutputStream(tempFile);
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesRead = 0;
                
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                }
                
                runContext.logger().debug("Exported {} bytes for item '{}' in format '{}'", 
                    totalBytesRead, rItemId, rFormat.name());
                
            } catch (IOException e) {
                throw new RuntimeException(
                    String.format("Failed to write exported content to temporary file for item '%s': %s", 
                        rItemId, e.getMessage()), e);
            } finally {
                // Close streams
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException e) {
                        runContext.logger().warn("Failed to close file output stream: {}", e.getMessage());
                    }
                }
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                        runContext.logger().warn("Failed to close input stream: {}", e.getMessage());
                    }
                }
            }
            
            // Store file in Kestra storage
            URI uri;
            try {
                uri = runContext.storage().putFile(tempFile);
            } catch (IOException e) {
                throw new RuntimeException(
                    String.format("Failed to store exported file in Kestra storage for item '%s': %s", 
                        rItemId, e.getMessage()), e);
            }
            
            runContext.logger().info("Successfully exported item '{}' to format '{}' and stored in storage", 
                rItemId, rFormat.name());
            return Output.builder().uri(uri).build();
            
        } catch (ApiException e) {
            // Handle any uncaught ApiException
            runContext.logger().error("Microsoft Graph API error while exporting item: {}", e.getMessage(), e);
            throw new RuntimeException(
                String.format("Failed to export item '%s' from drive '%s' to format '%s': %s", 
                    rItemId, rDriveId, rFormat.name(), e.getMessage()), e);
                    
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Re-throw our custom exceptions
            throw e;
        } catch (Exception e) {
            runContext.logger().error("Unexpected error while exporting item: {}", e.getMessage(), e);
            throw new RuntimeException(
                String.format("Unexpected error while exporting item '%s' from drive '%s' to format '%s': %s", 
                    rItemId, rDriveId, rFormat.name(), e.getMessage()), e);
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The URI of the exported file in Kestra's internal storage."
        )
        private final URI uri;
    }
}