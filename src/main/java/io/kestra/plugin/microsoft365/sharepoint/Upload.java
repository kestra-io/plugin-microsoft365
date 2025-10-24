package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotNull;
import java.io.InputStream;
import java.net.URI;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Upload a file to SharePoint",
    description = "Uploads a file to a SharePoint document library. Supports both simple upload (<4MB) and chunked upload for larger files."
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
        description = "The URI of the file to upload from Kestra's internal storage"
    )
    @NotNull
    private String from;

    @Schema(
        title = "Destination filename",
        description = "The name of the file in SharePoint"
    )
    @NotNull
    private String to;

    @Schema(
        title = "Parent folder ID",
        description = "The ID of the parent folder where the file will be uploaded. Use 'root' for the root of the document library."
    )
    @NotNull
    @Builder.Default
    private String parentId = "root";

    @Schema(
        title = "Conflict behavior",
        description = "What to do if a file with the same name already exists"
    )
    @Builder.Default
    private ConflictBehavior conflictBehavior = ConflictBehavior.FAIL;

    @Schema(
        title = "Chunk size for large files",
        description = "The size of each chunk in bytes for large file uploads. Default is 5MB."
    )
    @PluginProperty
    @Builder.Default
    private Long chunkSize = 5L * 1024 * 1024; // 5MB

    private static final long SIMPLE_UPLOAD_SIZE_LIMIT = 4L * 1024 * 1024; // 4MB

    @Override
    public Output run(RunContext runContext) throws Exception {
        SharepointConnection connection = this.connection(runContext);
        GraphServiceClient client = connection.createClient(runContext);
        String driveId = connection.getDriveId(runContext, client);

        String rTo = runContext.render(to);
        String rParentId = runContext.render(parentId);
        URI fromUri = new URI(runContext.render(from));

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
            title = "The ID of the uploaded item"
        )
        private String itemId;

        @Schema(
            title = "The name of the uploaded file"
        )
        private String name;

        @Schema(
            title = "The web URL of the uploaded file"
        )
        private String webUrl;

        @Schema(
            title = "The size of the uploaded file in bytes"
        )
        private Long size;
    }
}