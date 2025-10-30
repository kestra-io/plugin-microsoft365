package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.models.Drive;
import com.microsoft.graph.models.DriveCollectionResponse;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.microsoft365.sharepoint.models.Item;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "List items in a SharePoint document library",
            full = true,
            code = """
                id: microsoft365_sharepoint_list
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.microsoft365.sharepoint.List
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    folderId: "root"
                """
        ),
        @Example(
            title = "List items in a specific folder",
            full = true,
            code = """
                id: microsoft365_sharepoint_list_folder
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.microsoft365.sharepoint.List
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    folderId: "01BYE5RZ6QN3ZWBTURF3F43DSUNZYRZD5Q"
                """
        )
    },
    metrics = {
        @Metric(
            name = "count",
            type = Counter.TYPE,
            unit = "count",
            description = "Number of items returned"
        )
    }
)
@Schema(
    title = "List items in a SharePoint document library or folder."
)
public class List extends AbstractSharepointTask implements RunnableTask<List.Output> {

    @Schema(
        title = "Folder ID",
        description = "The ID of the folder to list items from. Use 'root' for the root of the document library."
    )
    @NotNull
    @Builder.Default
    private Property<String> folderId = Property.ofValue("root");

    @Schema(
        title = "The way you want to store the data",
        description = """
            FETCH - outputs the messages as an output
            FETCH_ONE - outputs the first message only as an output
            STORE - stores all messages to a file
            NONE - no output"""
    )
    @NotNull
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String rFolderId = runContext.render(folderId).as(String.class).orElse("root");
        FetchType rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        // Establish SharePoint connection and client
        SharepointConnection connection = this.connection(runContext);
        GraphServiceClient graphClient = connection.createClient(runContext);

        String siteId = connection.getSiteId(runContext);
        String driveId = connection.getDriveId(runContext, graphClient);

        // If driveId is not provided, resolve default document library
        if (driveId == null || driveId.isBlank()) {
            DriveCollectionResponse drivesResp = graphClient
                .sites()
                .bySiteId(siteId)
                .drives()
                .get();

            Drive targetDrive = Objects.requireNonNull(drivesResp.getValue()).stream()
                .filter(d -> "Documents".equalsIgnoreCase(d.getName()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Drive not found in site: " + siteId));

            driveId = targetDrive.getId();
            logger.info("Resolved default document library drive: {}", driveId);
        }

        // Fetch folder contents
        DriveItemCollectionResponse childrenResp = graphClient
            .drives()
            .byDriveId(driveId)
            .items()
            .byDriveItemId(rFolderId)
            .children()
            .get();

        java.util.List<DriveItem> driveItems = childrenResp.getValue();
        java.util.List<Item> items = driveItems.stream()
            .map(Item::fromDriveItem)
            .toList();

        // Emit metrics and log
        runContext.metric(Counter.of("count", items.size()));
        logger.info("Fetched {} items from SharePoint folder '{}'", items.size(), rFolderId);

        return switch (rFetchType) {
            case FETCH_ONE -> {
                if (items.isEmpty()) {
                    yield Output.builder()
                        .items(java.util.List.of())
                        .size(0)
                        .build();
                }
                yield Output.builder()
                    .items(java.util.List.of(items.getFirst()))
                    .item(items.getFirst())
                    .size(1)
                    .build();
            }
            case FETCH -> Output.builder()
                .items(items)
                .size(items.size())
                .build();
            case STORE -> {
                File tempFile = this.storeItems(runContext, items);
                yield Output.builder()
                    .uri(runContext.storage().putFile(tempFile))
                    .size(items.size())
                    .build();
            }
            case NONE -> Output.builder()
                .items(java.util.List.of())
                .size(0)
                .build();
        };
    }

    private File storeItems(RunContext runContext, java.util.List<Item> items) throws IOException {
        File tempFile = runContext.workingDir().createTempFile(".ion").toFile();

        try (BufferedWriter fileWriter = new BufferedWriter(new FileWriter(tempFile), FileSerde.BUFFER_SIZE)) {
            Flux<Item> flux = Flux.fromIterable(items);
            FileSerde.writeAll(fileWriter, flux).block();
        }

        return tempFile;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The list of items",
            description = "List of files and folders. Only populated when fetchType is FETCH or FETCH_ONE."
        )
        private final java.util.List<Item> items;

        @Schema(
            title = "Single item",
            description = "Single item. Only populated when fetchType is FETCH_ONE and an item exists."
        )
        private final Item item;

        @Schema(
            title = "URI of the stored items file",
            description = "URI pointing to the file containing all items. Only populated when fetchType is STORE."
        )
        private final URI uri;

        @Schema(
            title = "Total number of items",
            description = "Total count of items fetched from the folder."
        )
        private final Integer size;
    }
}
