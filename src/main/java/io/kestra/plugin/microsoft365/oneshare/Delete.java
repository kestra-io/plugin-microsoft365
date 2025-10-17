package io.kestra.plugin.microsoft365.oneshare;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Delete a file or folder from OneDrive or SharePoint."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Delete a file from OneDrive",
            code = """
                id: delete_onedrive_file
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.microsoft365.oneshare.Delete
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "01ABC123DEF456GHI789"
                """
        ),
        @Example(
            full = true,
            title = "List and delete specific files",
            code = """
                id: cleanup_old_files
                namespace: company.team

                tasks:
                  - id: list_files
                    type: io.kestra.plugin.microsoft365.oneshare.ListFiles
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "01TEMP_FOLDER"

                  - id: delete_file
                    type: io.kestra.plugin.microsoft365.oneshare.Delete
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "{{ outputs.list_files.files[0].id }}"
                """
        )
    }
)
public class Delete extends AbstractOneShareTask implements RunnableTask<VoidOutput> {

    @Schema(
        title = "The ID of the drive."
    )
    @NotNull
    private Property<String> driveId;

    @Schema(
        title = "The ID of the item (file or folder) to delete."
    )
    @NotNull
    private Property<String> itemId;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        GraphServiceClient client = this.graphClient(runContext);
        String rDriveId = runContext.render(this.driveId).as(String.class).orElseThrow();
        String rItemId = runContext.render(this.itemId).as(String.class).orElseThrow();

        runContext.logger().info("Deleting item '{}' from drive '{}'", rItemId, rDriveId);

        client.drives().byDriveId(rDriveId).items().byDriveItemId(rItemId).delete();

        return null;
    }
}