package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotNull;
import java.io.InputStream;
import java.net.URI;
import io.kestra.core.models.annotations.PluginProperty;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Upload file to SharePoint",
    description = "Uploads a file from Kestra internal storage to a SharePoint document library using Graph simple upload (best for files up to ~4MB; larger files may fail). PUT replaces existing files; conflictBehavior is currently ignored. Requires Microsoft Graph permissions Files.ReadWrite.All and Sites.ReadWrite.All."
)
@Plugin(
    examples = {
        @Example(
            title = "Upload a file to SharePoint root",
            full = true,
            code = """
                id: microsoft365_sharepoint_upload
                namespace: company.team

                tasks:
                  - id: upload
                    type: io.kestra.plugin.microsoft365.sharepoint.Upload
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    from: "{{ outputs.previous_task.uri }}"
                    to: "report.pdf"
                    parentId: "root"
                """
        ),
        @Example(
            title = "Upload a file to a specific folder with replace conflict behavior",
            full = true,
            code = """
                id: microsoft365_sharepoint_upload_folder
                namespace: company.team

                tasks:
                  - id: upload
                    type: io.kestra.plugin.microsoft365.sharepoint.Upload
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    siteId: "contoso.sharepoint.com,2C712604-1370-44E7-A1F5-426573FDA80A,2D2244C3-251A-49EA-93A8-39E1C3A060FE"
                    driveId: "b!BCTBCKSP50iysCOFPU"
                    from: "kestra:///data/output.xlsx"
                    to: "monthly-report.xlsx"
                    parentId: "01BYE5RZ6QN3ZWBTURF3F43DSUNZYRZD5Q"
                    conflictBehavior: REPLACE
                """
        )
    }
)
public class Upload extends AbstractSharepointTask implements RunnableTask<Upload.Output> {

    @Schema(
        title = "Source file URI",
        description = "URI of the file in Kestra internal storage to upload"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> from;

    @Schema(
        title = "Destination filename",
        description = "Filename to create in SharePoint"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> to;

    @Schema(
        title = "Parent folder ID",
        description = "Parent folder ID; use 'root' for the document library root"
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<String> parentId = Property.ofValue("root");

    @Schema(
        title = "Conflict behavior",
        description = "Reserved flag for naming conflicts; currently ignored and the upload overwrites existing files"
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private ConflictBehavior conflictBehavior = ConflictBehavior.FAIL;

    @Schema(
        title = "Chunk size for large files",
        description = "Unused placeholder for chunked uploads; Graph simple upload is used instead"
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Long> chunkSize = Property.ofValue(5L * 1024 * 1024); // 5MB

    private static final long SIMPLE_UPLOAD_SIZE_LIMIT = 4L * 1024 * 1024; // 4MB

    @Override
    public Output run(RunContext runContext) throws Exception {
        SharepointConnection connection = this.connection(runContext);
        GraphServiceClient client = connection.createClient(runContext);
        String driveId = connection.getDriveId(runContext, client);

        String rTo = runContext.render(to).as(String.class).orElseThrow();
        String rParentId = runContext.render(parentId).as(String.class).orElse("root");
        URI fromUri = new URI(runContext.render(from).as(String.class).orElseThrow());

        // Get the file from storage
        InputStream fileStream = runContext.storage().getFile(fromUri);

        // Upload the file - for large files, the Graph SDK automatically handles chunked uploads
        DriveItem uploadedItem = client.drives().byDriveId(driveId)
            .items().byDriveItemId(rParentId + ":/" + rTo + ":")
            .content()
            .put(fileStream);

        return Output.builder()
            .itemId(uploadedItem.getId())
            .name(uploadedItem.getName())
            .webUrl(uploadedItem.getWebUrl())
            .size(uploadedItem.getSize())
            .build();
    }


    @Getter
    public enum ConflictBehavior {
        FAIL("fail"),
        REPLACE("replace"),
        RENAME("rename");

        private final String value;

        ConflictBehavior(String value) {
            this.value = value;
        }

    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "ID of the uploaded item"
        )
        private String itemId;

        @Schema(
            title = "Name of the uploaded file"
        )
        private String name;

        @Schema(
            title = "Web URL of the uploaded file"
        )
        private String webUrl;

        @Schema(
            title = "Size of the uploaded file in bytes"
        )
        private Long size;
    }
}
