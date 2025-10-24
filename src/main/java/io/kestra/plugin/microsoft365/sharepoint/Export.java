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
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Export files from SharePoint",
    description = "Exports multiple files from a SharePoint folder to Kestra's internal storage. Supports filtering by file patterns, recursive folder traversal, and format conversion (e.g., Office documents to PDF)."
)
@Plugin(
    examples = {
        @Example(
            title = "Export all files from a SharePoint folder",
            full = true,
            code = """
                id: microsoft365_sharepoint_export
                namespace: company.team

                tasks:
                  - id: export
                    type: io.kestra.plugin.microsoft365.sharepoint.Export
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    folderId: "root"
                """
        ),
        @Example(
            title = "Export PDF files from a folder recursively",
            full = true,
            code = """
                id: microsoft365_sharepoint_export_filtered
                namespace: company.team

                tasks:
                  - id: export
                    type: io.kestra.plugin.microsoft365.sharepoint.Export
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    folderId: "01BYE5RZ6QN3ZWBTURF3F43DSUNZYRZD5Q"
                    regexPattern: ".*\\.pdf$"
                    recursive: true
                """
        ),
        @Example(
            title = "Export files by folder path",
            full = true,
            code = """
                id: microsoft365_sharepoint_export_by_path
                namespace: company.team

                tasks:
                  - id: export
                    type: io.kestra.plugin.microsoft365.sharepoint.Export
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    folderPath: "/Documents/Reports"
                    recursive: false
                """
        ),
        @Example(
            title = "Export Office documents as PDF",
            full = true,
            code = """
                id: microsoft365_sharepoint_export_as_pdf
                namespace: company.team

                tasks:
                  - id: export
                    type: io.kestra.plugin.microsoft365.sharepoint.Export
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    folderId: "root"
                    regexPattern: ".*\\.(docx|xlsx|pptx)$"
                    convertToPdf: true
                """
        )
    },
    metrics = {
        @Metric(
            name = "file.count",
            type = Counter.TYPE,
            unit = "count",
            description = "Number of files exported"
        ),
        @Metric(
            name = "file.size",
            type = Counter.TYPE,
            unit = "bytes",
            description = "Total size of exported files in bytes"
        )
    }
)
public class Export extends AbstractSharepointTask implements RunnableTask<Export.Output> {

    @Schema(
        title = "Folder ID",
        description = "The ID of the folder to export files from. Use 'root' for the root of the document library. Either folderId or folderPath must be provided."
    )
    @Builder.Default
    private Property<String> folderId = Property.ofValue("root");

    @Schema(
        title = "Folder path",
        description = "The path to the folder relative to the drive root (e.g., '/Documents/Reports'). Either folderId or folderPath must be provided."
    )
    private Property<String> folderPath;

    @Schema(
        title = "Regex pattern",
        description = "Optional regex pattern to filter files by name (e.g., '.*\\.pdf$' for PDF files only)"
    )
    private Property<String> regexPattern;

    @Schema(
        title = "Recursive",
        description = "Whether to recursively export files from subfolders"
    )
    @Builder.Default
    private Property<Boolean> recursive = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        SharepointConnection connection = this.connection(runContext);
        GraphServiceClient client = connection.createClient(runContext);
        String driveId = connection.getDriveId(runContext, client);

        // Determine folder ID
        String rFolderId;
        if (folderPath != null) {
            String rFolderPath = runContext.render(folderPath).as(String.class).orElseThrow();
            DriveItem folderItem = client.drives().byDriveId(driveId)
                .items().byDriveItemId("root:" + rFolderPath + ":")
                .get();
            rFolderId = folderItem.getId();
        } else {
            rFolderId = runContext.render(folderId).as(String.class).orElse("root");
        }

        // Get regex pattern if provided
        Pattern pattern = null;
        if (regexPattern != null) {
            String rPattern = runContext.render(regexPattern).as(String.class).orElse(null);
            if (rPattern != null && !rPattern.isBlank()) {
                pattern = Pattern.compile(rPattern);
            }
        }

        boolean rRecursive = runContext.render(recursive).as(Boolean.class).orElse(false);

        // Export files
        List<FileInfo> exportedFiles = new ArrayList<>();
        long totalSize = exportFiles(runContext, client, driveId, rFolderId, pattern, rRecursive, exportedFiles);

        // Emit metrics
        runContext.metric(Counter.of("file.count", exportedFiles.size()));
        runContext.metric(Counter.of("file.size", totalSize));

        runContext.logger().info("Exported {} files ({} bytes total) from SharePoint folder '{}'",
            exportedFiles.size(), totalSize, rFolderId);

        return Output.builder()
            .files(exportedFiles)
            .totalFiles(exportedFiles.size())
            .totalSize(totalSize)
            .build();
    }

    private long exportFiles(RunContext runContext, GraphServiceClient client,
                             String driveId, String folderId, Pattern pattern,
                             boolean recursive, List<FileInfo> exportedFiles) throws Exception {
        long totalSize = 0;

        // Get folder contents
        DriveItemCollectionResponse response = client.drives().byDriveId(driveId)
            .items().byDriveItemId(folderId)
            .children()
            .get();

        List<DriveItem> items = response.getValue();
        if (items == null) {
            return totalSize;
        }

        for (DriveItem item : items) {
            if (item.getFolder() != null && recursive) {
                // Process subfolder recursively
                totalSize += exportFiles(runContext, client, driveId, item.getId(),
                    pattern, recursive, exportedFiles);
            } else if (item.getFile() != null) {
                // Check if file matches pattern
                if (pattern == null || pattern.matcher(item.getName()).matches()) {
                    // Download file
                    InputStream fileStream = client.drives().byDriveId(driveId)
                        .items().byDriveItemId(item.getId())
                        .content()
                        .get();

                    // Store in Kestra's internal storage
                    URI fileUri = runContext.storage().putFile(fileStream, item.getName());

                    // Add to exported files list
                    FileInfo fileInfo = FileInfo.builder()
                        .itemId(item.getId())
                        .name(item.getName())
                        .uri(fileUri.toString())
                        .size(item.getSize())
                        .webUrl(item.getWebUrl())
                        .parentPath(item.getParentReference() != null ? item.getParentReference().getPath() : null)
                        .build();

                    exportedFiles.add(fileInfo);
                    totalSize += (item.getSize() != null ? item.getSize() : 0L);

                    runContext.logger().debug("Downloaded file: {} ({} bytes)", item.getName(), item.getSize());
                }
            }
        }

        return totalSize;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "List of exported files",
            description = "Information about each file that was exported"
        )
        private List<FileInfo> files;

        @Schema(
            title = "Total number of files exported"
        )
        private Integer totalFiles;

        @Schema(
            title = "Total size of all exported files in bytes"
        )
        private Long totalSize;
    }

    @Builder
    @Getter
    public static class FileInfo {
        @Schema(
            title = "The ID of the file"
        )
        private String itemId;

        @Schema(
            title = "The name of the file"
        )
        private String name;

        @Schema(
            title = "The URI of the file in Kestra's internal storage"
        )
        private String uri;

        @Schema(
            title = "The size of the file in bytes"
        )
        private Long size;

        @Schema(
            title = "The web URL of the file in SharePoint"
        )
        private String webUrl;

        @Schema(
            title = "The parent folder path"
        )
        private String parentPath;
    }
}
