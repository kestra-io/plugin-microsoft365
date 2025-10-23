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
            full = true,
            code = """
                id: microsoft365_sharepoint_download
                namespace: company.team

                tasks:
                  - id: download
                    type: io.kestra.plugin.microsoft365.sharepoint.Download
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    itemId: "01BYE5RZ6QN3ZWBTURF3F43DSUNZYRZD5Q"
                """
        )
    },
    metrics = {
        @Metric(
            name = "size",
            type = Counter.TYPE,
            unit = "bytes",
            description = "The size of the downloaded file in bytes"
        )
    }
)
@Schema(
    title = "Download a file from SharePoint."
)
public class Download extends AbstractMicrosoftGraphIdentityConnection implements RunnableTask<Download.Output> {
    
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
        title = "The item ID to download.",
        description = "The unique identifier of the file to download."
    )
    @NotNull
    private Property<String> itemId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        
        String rSiteId = runContext.render(siteId).as(String.class).orElseThrow();
        String rDriveId = runContext.render(driveId).as(String.class).orElseThrow();
        String rItemId = runContext.render(itemId).as(String.class).orElseThrow();

        GraphServiceClient graphClient = createGraphClient(runContext);

        logger.debug("Downloading item '{}' from SharePoint site '{}', drive '{}'", rItemId, rSiteId, rDriveId);

        // Get file metadata
        com.microsoft.graph.models.DriveItem driveItem = graphClient.drives().byDriveId(rDriveId)
            .items().byDriveItemId(rItemId)
            .get();

        // Download file content
        InputStream contentStream = graphClient.drives().byDriveId(rDriveId)
            .items().byDriveItemId(rItemId)
            .content()
            .get();

        File tempFile = runContext.workingDir().createTempFile().toFile();
        
        try (BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(tempFile))) {
            contentStream.transferTo(outputStream);
            outputStream.flush();
        }

        Long fileSize = driveItem.getSize();
        if (fileSize != null) {
            runContext.metric(Counter.of("size", fileSize));
        }

        logger.info("Successfully downloaded file '{}' ({})", driveItem.getName(), fileSize != null ? fileSize + " bytes" : "unknown size");

        return Output.builder()
            .uri(runContext.storage().putFile(tempFile))
            .itemId(rItemId)
            .itemName(driveItem.getName())
            .size(fileSize)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The URI of the downloaded file on Kestra storage."
        )
        private final URI uri;

        @Schema(
            title = "The ID of the downloaded item."
        )
        private final String itemId;

        @Schema(
            title = "The name of the downloaded item."
        )
        private final String itemName;

        @Schema(
            title = "The size of the downloaded file in bytes."
        )
        private final Long size;
    }
}
