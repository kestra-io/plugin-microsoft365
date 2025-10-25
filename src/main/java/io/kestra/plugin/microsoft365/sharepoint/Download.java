package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Download a file from SharePoint",
    description = "Downloads a file from a SharePoint document library and stores it in Kestra's internal storage."
)
@Plugin(
    examples = {
        @Example(
            title = "Download a file from SharePoint",
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
        ),
        @Example(
            title = "Download a file by path",
            full = true,
            code = """
                id: microsoft365_sharepoint_download_by_path
                namespace: company.team

                tasks:
                  - id: download
                    type: io.kestra.plugin.microsoft365.sharepoint.Download
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    itemPath: "/Documents/report.pdf"
                """
        )
    }
)
public class Download extends AbstractSharepointTask implements RunnableTask<Download.Output> {

    @Schema(
        title = "Item ID",
        description = "The ID of the file to download. Either itemId or itemPath must be provided."
    )
    private Property<String> itemId;

    @Schema(
        title = "Item path",
        description = "The path to the file relative to the drive root (e.g., '/Documents/file.txt'). Either itemId or itemPath must be provided."
    )
    private Property<String> itemPath;

    @Override
    public Output run(RunContext runContext) throws Exception {
        SharepointConnection connection = this.connection(runContext);
        GraphServiceClient client = connection.createClient(runContext);
        String driveId = connection.getDriveId(runContext, client);

        // Get the file metadata with downloadUrl
        DriveItemCollectionResponse driveItem;
        if (itemId != null) {
            String rItemId = runContext.render(itemId).as(String.class).orElseThrow();
            driveItem = client.drives().byDriveId(driveId)
                .items().byDriveItemId(rItemId)
                .children().get(
                );
        } else if (itemPath != null) {
            String rItemPath = runContext.render(itemPath).as(String.class).orElseThrow();
            driveItem = client.drives().byDriveId(driveId)
                .items().byDriveItemId("root:" + rItemPath + ":")
                .children().get(
                );
        } else {
            throw new IllegalArgumentException("Either itemId or itemPath must be provided");
        }

        // Extract the first item from the collection response
        if (driveItem.getValue() == null || driveItem.getValue().isEmpty()) {
            throw new RuntimeException("No items found in the response");
        }
        DriveItem firstItem = driveItem.getValue().getFirst();

        // Get the download URL from the drive item metadata
        Object downloadUrlObj = firstItem.getAdditionalData().get("@microsoft.graph.downloadUrl");
        if (downloadUrlObj == null) {
            throw new RuntimeException("Download URL not available. The file might be too large or unavailable.");
        }
        String downloadUrl = downloadUrlObj.toString();

        // Use standard HTTP client to download the file
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(downloadUrl))
            .GET()
            .build();

        HttpResponse<InputStream> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to download file. Status code: " + response.statusCode());
        }

        InputStream fileStream = response.body();

        URI fileUri = runContext.storage().putFile(fileStream, firstItem.getName());

        return Output.builder()
            .itemId(firstItem.getId())
            .name(firstItem.getName())
            .uri(fileUri.toString())
            .size(firstItem.getSize())
            .webUrl(firstItem.getWebUrl())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The ID of the downloaded item"
        )
        private String itemId;

        @Schema(
            title = "The name of the downloaded file"
        )
        private String name;

        @Schema(
            title = "The URI of the file in Kestra's internal storage"
        )
        private String uri;

        @Schema(
            title = "The size of the downloaded file in bytes"
        )
        private Long size;

        @Schema(
            title = "The web URL of the file in SharePoint"
        )
        private String webUrl;
    }
}