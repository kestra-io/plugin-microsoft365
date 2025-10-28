package io.kestra.plugin.microsoft365.oneshare;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.File;
import com.microsoft.graph.models.Folder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.microsoft365.oneshare.models.OneShareFile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Create a file or folder in OneDrive or SharePoint."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Create a folder in OneDrive",
            code = """
                id: create_onedrive_folder
                namespace: company.team

                tasks:
                  - id: create
                    type: io.kestra.plugin.microsoft365.oneshare.Create
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    parentId: "root"
                    name: "new-folder"
                    folder: true
                """
        ),
        @Example(
            full = true,
            title = "Create a text file with content",
            code = """
                id: create_text_file
                namespace: company.team

                tasks:
                  - id: create_file
                    type: io.kestra.plugin.microsoft365.oneshare.Create
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    parentId: "root"
                    name: "readme.txt"
                    content: "Hello World!"
                """
        )
    }
)
public class Create extends AbstractOneShareTask implements RunnableTask<Create.Output> {

    @Schema(
        title = "The ID of the parent folder. If not provided, the root of the drive is used."
    )
    private Property<String> parentId;

    @Schema(
        title = "The name of the file or folder to create."
    )
    @NotNull
    private Property<String> name;

    @Schema(
        title = "Set to true to create a folder."
    )
    @Builder.Default
    private Property<Boolean> folder = Property.ofValue(false);

    @Schema(
        title = "Content of the file to create."
    )
    private Property<String> content;

    @Override
    public Output run(RunContext runContext) throws Exception {
        GraphServiceClient client = this.graphClient(runContext);
        String rDriveId = runContext.render(this.driveId).as(String.class).orElseThrow();
        String rParentId = runContext.render(this.parentId).as(String.class).orElse("root");
        String rName = runContext.render(this.name).as(String.class).orElseThrow();
        boolean rIsFolder = runContext.render(this.folder).as(Boolean.class).orElse(false);
        String rContent = runContext.render(this.content).as(String.class).orElse(null);

        runContext.logger().info("Creating a {} in drive '{}' with name '{}'", rIsFolder ? "folder" : "file", rDriveId, rName);

        DriveItem result;
        if (rIsFolder) {
            // Create a folder using POST /children with folder facet
            DriveItem driveItem = new DriveItem();
            driveItem.setName(rName);
            driveItem.setFolder(new Folder());
            result = client.drives().byDriveId(rDriveId).items().byDriveItemId(rParentId).children().post(driveItem);
        } else {
            // For files with content, use PUT content endpoint (recommended approach)
            if (rContent != null && !rContent.isEmpty()) {
                byte[] bytes = rContent.getBytes(StandardCharsets.UTF_8);
                String itemPath = rParentId + ":/" + rName + ":";
                result = client.drives().byDriveId(rDriveId).items().byDriveItemId(itemPath).content().put(new ByteArrayInputStream(bytes));
            } else {
                // For empty files, create using POST /children with file facet
                DriveItem driveItem = new DriveItem();
                driveItem.setName(rName);
                driveItem.setFile(new File());
                result = client.drives().byDriveId(rDriveId).items().byDriveItemId(rParentId).children().post(driveItem);
            }
        }

        return Output.builder().file(OneShareFile.of(result)).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The created file or folder metadata."
        )
        private final OneShareFile file;
    }
}
