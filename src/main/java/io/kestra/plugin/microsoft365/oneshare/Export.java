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
    title = "Export a file from OneDrive or SharePoint to a different format."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Export file to PDF format",
            code = """
                id: export_to_pdf
                namespace: company.team

                tasks:
                  - id: export
                    type: io.kestra.plugin.microsoft365.oneshare.Export
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "01ABC123DEF456GHI789"
                    format: PDF
                """
        ),
        @Example(
            full = true,
            title = "Export file to HTML format",
            code = """
                id: export_to_html
                namespace: company.team

                tasks:
                  - id: export_html
                    type: io.kestra.plugin.microsoft365.oneshare.Export
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    itemId: "01EXCEL123456789"
                    format: HTML
                """
        )
    }
)
public class Export extends AbstractOneShareTask implements RunnableTask<Export.Output> {

    public enum ExportFormat {
        @Schema(description = "Converts the item into PDF format. Supports: doc, docx, dot, dotx, dotm, dsn, dwg, eml, epub, fluidframework, form, htm, html, loop, loot, markdown, md, msg, note, odp, ods, odt, page, pps, ppsx, ppt, pptx, pulse, rtf, task, tif, tiff, wbtx, whiteboard, xls, xlsm, xlsx")
        PDF,
        
        @Schema(description = "Converts the item into HTML format. Supports: loop, fluid, wbtx")
        HTML
    }

    @Schema(
        title = "The ID of the item (file) to export."
    )
    @NotNull
    private Property<String> itemId;

    @Schema(
        title = "The format to export the file to.",
        description = "PDF format supports most file types (doc, docx, ppt, xlsx, etc.). HTML format supports loop, fluid, and wbtx files only."
    )
    @NotNull
    private Property<ExportFormat> format;

    @Override
    public Output run(RunContext runContext) throws Exception {
        GraphServiceClient client = this.graphClient(runContext);
        String rDriveId = runContext.render(this.driveId).as(String.class).orElseThrow();
        String rItemId = runContext.render(this.itemId).as(String.class).orElseThrow();
        ExportFormat rFormat = runContext.render(this.format).as(ExportFormat.class).orElseThrow();

        runContext.logger().info("Exporting item '{}' from drive '{}' to format '{}'", rItemId, rDriveId, rFormat.name());

        try (InputStream inputStream = client.drives().byDriveId(rDriveId).items().byDriveItemId(rItemId).content()
            .get(requestConfiguration -> {
                requestConfiguration.queryParameters.format = rFormat.name().toLowerCase();
            })) {
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
            title = "The URI of the exported file in Kestra's internal storage."
        )
        private final URI uri;
    }
}