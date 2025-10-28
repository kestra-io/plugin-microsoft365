package io.kestra.plugin.microsoft365.oneshare;

import com.microsoft.graph.serviceclient.GraphServiceClient;
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

        runContext.logger().info("Downloading item '{}' from drive '{}'", rItemId, rDriveId);

        try (InputStream inputStream = client.drives().byDriveId(rDriveId).items().byDriveItemId(rItemId).content().get()) {
            File tempFile = runContext.workingDir().createTempFile().toFile();
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                inputStream.transferTo(fos);
            }
            URI uri = runContext.storage().putFile(tempFile);
            return Output.builder().uri(uri).build();
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
