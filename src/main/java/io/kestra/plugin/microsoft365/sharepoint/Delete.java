package io.kestra.plugin.microsoft365.sharepoint;

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
                id: microsoft365_sharepoint_delete
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.microsoft365.sharepoint.Delete
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    itemId: "01BYE5RZ6QN3ZWBTURF3F43DSUNZYRZD5Q"
                """
        )
    }
)
@Schema(
    title = "Delete a file or folder from SharePoint.",
    description = "Required Microsoft Graph application permissions: Files.ReadWrite.All and Sites.ReadWrite.All."
)
public class Delete extends AbstractSharepointTask implements RunnableTask<Delete.Output> {

    @Schema(
        title = "The item ID to delete.",
        description = "The unique identifier of the file or folder to delete."
    )
    @NotNull
    private Property<String> itemId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        SharepointConnection connection = this.connection(runContext);
        GraphServiceClient client = connection.createClient(runContext);
        String driveId = connection.getDriveId(runContext, client);

        String rItemId = runContext.render(itemId).as(String.class).orElseThrow();

        client.drives().byDriveId(driveId)
            .items().byDriveItemId(rItemId)
            .delete();
        logger.info("Successfully deleted item '{}'", rItemId);

        return Output.builder()
            .itemId(rItemId)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The ID of the deleted item.",
            description = "The unique identifier of the deleted file or folder."
        )
        private final String itemId;
    }
}
