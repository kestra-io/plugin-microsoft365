package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.microsoft365.AbstractMicrosoftGraphIdentityConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Export a SharePoint document to PDF",
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
                    itemId: "01BYE5RZ6QN3ZWBTURF3F43DSUNZYRZD5Q"
                    format: pdf
                """
        )
    },
    metrics = {
        @Metric(
            name = "size",
            type = Counter.TYPE,
            unit = "bytes",
            description = "The size of the exported file in bytes"
        )
    }
)
@Schema(
    title = "Export a file from SharePoint to another format.",
    description = "Export Office documents (Word, Excel, PowerPoint) to different formats like PDF."
)
public class Export extends AbstractMicrosoftGraphIdentityConnection implements RunnableTask<Export.Output> {
    
    @Schema(
        title = "The SharePoint site ID.",
        description = "The unique identifier of the SharePoint site."
    )
    @NotNull
    private Property<String> siteId;

    @Schema(
        title = "The SharePoint drive ID.",
        description = "The unique identifier of the SharePoint document library (drive)."
    )
    @NotNull
    private Property<String> driveId;

    @Schema(
        title = "The item ID to export.",
        description = "The unique identifier of the file to export."
    )
    @NotNull
    private Property<String> itemId;

    @Schema(
        title = "The format to export to.",
        description = "The target format for the export (e.g., 'pdf')."
    )
    @NotNull
    private Property<String> format;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        
        String rSiteId = runContext.render(siteId).as(String.class).orElseThrow();
        String rDriveId = runContext.render(driveId).as(String.class).orElseThrow();
        String rItemId = runContext.render(itemId).as(String.class).orElseThrow();
        String rFormat = runContext.render(format).as(String.class).orElseThrow();

        GraphServiceClient graphClient = createGraphClient(runContext);

        logger.debug("Exporting item '{}' to format '{}' from SharePoint site '{}', drive '{}'", 
            rItemId, rFormat, rSiteId, rDriveId);

        // Get file metadata
        com.microsoft.graph.models.DriveItem driveItem = graphClient.drives().byDriveId(rDriveId)
            .items().byDriveItemId(rItemId)
            .get();

        // Export file content with format parameter
        // Note: The Microsoft Graph API uses query parameters for format conversion
        InputStream contentStream = graphClient.drives().byDriveId(rDriveId)
            .items().byDriveItemId(rItemId)
            .content()
            .get(requestConfiguration -> {
                requestConfiguration.queryParameters.format = rFormat;
            });

        File tempFile = runContext.workingDir().createTempFile().toFile();
        
        try (BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(tempFile))) {
            contentStream.transferTo(outputStream);
            outputStream.flush();
        }

        long fileSize = tempFile.length();
        runContext.metric(Counter.of("size", fileSize));

        logger.info("Successfully exported file '{}' to format '{}' ({} bytes)", 
            driveItem.getName(), rFormat, fileSize);

        return Output.builder()
            .uri(runContext.storage().putFile(tempFile))
            .itemId(rItemId)
            .itemName(driveItem.getName())
            .format(rFormat)
            .size(fileSize)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The URI of the exported file on Kestra storage."
        )
        private final URI uri;

        @Schema(
            title = "The ID of the exported item."
        )
        private final String itemId;

        @Schema(
            title = "The name of the original item."
        )
        private final String itemName;

        @Schema(
            title = "The format the file was exported to."
        )
        private final String format;

        @Schema(
            title = "The size of the exported file in bytes."
        )
        private final Long size;
    }
}
