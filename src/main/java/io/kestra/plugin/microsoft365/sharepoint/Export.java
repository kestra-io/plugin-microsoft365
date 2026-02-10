package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.InputStream;
import java.net.URI;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Export a SharePoint file to another format",
    description = "Exports a single file from SharePoint (e.g., Office document) to another format such as PDF or HTML using Microsoft Graph API. Required Microsoft Graph application permissions: Files.Read.All and Sites.Read.All."
)
@Plugin(
    examples = {
        @Example(
            title = "Export a Word document to PDF",
            full = true,
            code = """
                id: microsoft365_sharepoint_export_file
                namespace: company.team

                tasks:
                  - id: export
                    type: io.kestra.plugin.microsoft365.sharepoint.Export
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!Xyz123..."
                    itemId: "01ABCDEF123456789"
                    format: "pdf"
                """
        ),
        @Example(
            title = "Export an Excel file to PDF",
            full = true,
            code = """
                id: microsoft365_sharepoint_export_excel
                namespace: company.team

                tasks:
                  - id: export
                    type: io.kestra.plugin.microsoft365.sharepoint.Export
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    itemPath: "/Documents/report.xlsx"
                    format: "pdf"
                """
        )
    }
)
public class Export extends AbstractSharepointTask implements RunnableTask<Export.Output> {

    @Schema(
        title = "Item ID",
        description = "The ID of the file in SharePoint to export. Either itemId or itemPath must be provided."
    )
    private Property<String> itemId;

    @Schema(
        title = "Item path",
        description = "The path to the file relative to the drive root (e.g., '/Documents/file.docx'). Either itemId or itemPath must be provided."
    )
    private Property<String> itemPath;

    @Schema(
        title = "Output format",
        description = "The format to convert the file to. Supported values: 'pdf' or 'html'."
    )
    @NotNull
    private Property<FormatType> format;

    @Override
    public Output run(RunContext runContext) throws Exception {
        SharepointConnection connection = this.connection(runContext);
        GraphServiceClient client = connection.createClient(runContext);
        String driveId = connection.getDriveId(runContext, client);

        // Get the file metadata
        DriveItem driveItem;
        if (itemId != null) {
            String rItemId = runContext.render(itemId).as(String.class).orElseThrow();
            driveItem = client.drives().byDriveId(driveId)
                .items().byDriveItemId(rItemId)
                .get();
        } else if (itemPath != null) {
            String rItemPath = runContext.render(itemPath).as(String.class).orElseThrow();
            driveItem = client.drives().byDriveId(driveId)
                .items().byDriveItemId("root:" + rItemPath + ":")
                .get();
        } else {
            throw new IllegalArgumentException("Either itemId or itemPath must be provided");
        }

        // Get and validate format
        FormatType formatEnum = runContext.render(format).as(FormatType.class).orElseThrow();
        String rFormat = formatEnum.name().toLowerCase();

        // Download the file content with format conversion
        // The Microsoft Graph API endpoint is: GET /drives/{driveId}/items/{itemId}/content?format={format}
        InputStream fileStream = client.drives().byDriveId(driveId)
            .items().byDriveItemId(driveItem.getId())
            .content()
            .get(requestConfiguration -> {
                requestConfiguration.queryParameters.format = rFormat;
            });

        // Update filename with new extension
        String fileName = driveItem.getName();
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            fileName = fileName.substring(0, lastDotIndex) + "." + rFormat;
        } else {
            fileName = fileName + "." + rFormat;
        }

        // Store the file in Kestra's internal storage
        URI fileUri = runContext.storage().putFile(fileStream, fileName);

        runContext.logger().info("Exported file '{}' to format '{}' as '{}'", driveItem.getName(), rFormat, fileName);

        return Output.builder()
            .itemId(driveItem.getId())
            .originalName(driveItem.getName())
            .name(fileName)
            .uri(fileUri.toString())
            .originalSize(driveItem.getSize())
            .webUrl(driveItem.getWebUrl())
            .format(rFormat)
            .build();
    }

    public enum FormatType {
        HTML,
        PDF
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The ID of the file"
        )
        private String itemId;

        @Schema(
            title = "The original name of the file in SharePoint"
        )
        private String originalName;

        @Schema(
            title = "The name of the exported file with new extension"
        )
        private String name;

        @Schema(
            title = "The URI of the file in Kestra's internal storage"
        )
        private String uri;

        @Schema(
            title = "The size of the original file in bytes (before conversion)"
        )
        private Long originalSize;

        @Schema(
            title = "The web URL of the file in SharePoint"
        )
        private String webUrl;

        @Schema(
            title = "The format the file was converted to"
        )
        private String format;
    }
}
