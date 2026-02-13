package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.ItemReference;
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
import org.slf4j.Logger;

import java.util.Objects;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Move an item to another folder in SharePoint",
            full = true,
            code = """
                id: microsoft365_sharepoint_move
                namespace: company.team

                tasks:
                  - id: move
                    type: io.kestra.plugin.microsoft365.sharepoint.Move
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    itemId: "01BYE5RZ6QN3ZWBTURF3F43DSUNZYRZD5Q"
                    destinationParentId: "01BYE5RZ6QN3ZWBTURF3F43DSUNZYRZD5Z"
                    newName: "renamed-document.txt"
                """
        )
    }
)
@Schema(
    title = "Move a file or folder in SharePoint.",
    description = "Moves a DriveItem to another parent folder. Optionally renames the item. Required Microsoft Graph application permissions: Files.ReadWrite.All and Sites.ReadWrite.All."
)
public class Move extends AbstractSharepointTask implements RunnableTask<Move.Output> {

    @Schema(
        title = "Item ID",
        description = "The unique identifier of the file or folder to move."
    )
    @NotNull
    private Property<String> itemId;

    @Schema(
        title = "Destination parent folder ID",
        description = "The ID of the destination folder (parent) where the item will be moved."
    )
    @NotNull
    private Property<String> destinationParentId;

    @Schema(
        title = "New name",
        description = "Optional new name for the moved item."
    )
    private Property<String> newName;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        SharepointConnection connection = this.connection(runContext);
        GraphServiceClient client = connection.createClient(runContext);
        String driveId = connection.getDriveId(runContext, client);

        String rItemId = runContext.render(itemId).as(String.class).orElseThrow();
        String rDestinationParentId = runContext.render(destinationParentId).as(String.class).orElseThrow();
        String rNewName = runContext.render(newName).as(String.class).orElse(null);

        DriveItem update = new DriveItem();

        // Move: PATCH driveItem with new parentReference
        ItemReference parentRef = new ItemReference();
        parentRef.setId(rDestinationParentId);
        update.setParentReference(parentRef);

        // Optional rename
        if (rNewName != null && !rNewName.isBlank()) {
            update.setName(rNewName);
        }

        DriveItem moved = client
            .drives().byDriveId(driveId)
            .items().byDriveItemId(rItemId)
            .patch(update);

        logger.info(
            "Successfully moved item '{}' to parent '{}'{}",
            rItemId,
            rDestinationParentId,
            (rNewName != null && !rNewName.isBlank()) ? " and renamed to '" + rNewName + "'" : ""
        );

        return Output.builder()
            .itemId(Objects.requireNonNull(moved).getId())
            .itemName(moved.getName())
            .parentId(moved.getParentReference() != null ? moved.getParentReference().getId() : rDestinationParentId)
            .webUrl(moved.getWebUrl())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The ID of the moved item.",
            description = "The unique identifier of the moved file or folder."
        )
        private final String itemId;

        @Schema(
            title = "The name of the moved item.",
            description = "The current name of the moved item (after optional rename)."
        )
        private final String itemName;

        @Schema(
            title = "The destination parent ID.",
            description = "The ID of the parent folder where the item was moved."
        )
        private final String parentId;

        @Schema(
            title = "The web URL of the moved item."
        )
        private final String webUrl;
    }
}
