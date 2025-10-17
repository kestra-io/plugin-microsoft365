package io.kestra.plugin.microsoft365.sharepoint;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import com.microsoft.graph.core.ClientException;

import java.net.URI;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Export a SharePoint file to PDF format.",
            code = {
                "siteId: \"your-site-id\"",
                "driveId: \"your-drive-id\"",
                "itemId: \"your-item-id\"",
                "format: \"pdf\""
            }
        )
    }
)
@Schema(
    title = "Export a file to another format in a SharePoint document library.",
    description = "This task allows you to export a file to another format (e.g., Office document → PDF) from a SharePoint document library."
)
public class Export extends Task implements RunnableTask<Export.Output> {
    @Schema(
        title = "The SharePoint site ID.",
        description = "The unique identifier of the SharePoint site."
    )
    @PluginProperty(dynamic = true)
    private Property<String> siteId;

    @Schema(
        title = "The SharePoint drive ID.",
        description = "The unique identifier of the SharePoint document library (drive)."
    )
    @PluginProperty(dynamic = true)
    private Property<String> driveId;

    @Schema(
        title = "The item ID.",
        description = "The unique identifier of the file to export."
    )
    @PluginProperty(dynamic = true)
    private Property<String> itemId;

    @Schema(
        title = "The export format.",
        description = "The format to export the file to (e.g., pdf, epub, etc.)."
    )
    @PluginProperty(dynamic = true)
    private Property<String> format;

    @Override
    public Export.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        
        String renderedSiteId = runContext.render(siteId).as(String.class).orElse(null);
        String renderedDriveId = runContext.render(driveId).as(String.class).orElse(null);
        String renderedItemId = runContext.render(itemId).as(String.class).orElse(null);
        String renderedFormat = runContext.render(format).as(String.class).orElse(null);
        
        logger.debug("Exporting file '{}' from SharePoint site '{}', drive '{}' to format '{}'", 
            renderedItemId, renderedSiteId, renderedDriveId, renderedFormat);
        
        try {
            com.microsoft.graph.requests.GraphServiceClient<?> graphClient = GraphClientProvider.getClient();
            
            // Export the file content to the specified format
            java.io.InputStream contentStream = graphClient.sites(renderedSiteId)
                .drives(renderedDriveId)
                .items(renderedItemId)
                .content()
                .buildRequest()
                .get(new com.microsoft.graph.options.QueryOption("format", renderedFormat));
            
            // Convert stream to string
            String exportedContent = new String(contentStream.readAllBytes());
            
            return Output.builder()
                .itemId(renderedItemId)
                .uri(new URI("https://graph.microsoft.com/v1.0/sites/" + renderedSiteId + 
                    "/drives/" + renderedDriveId + "/items/" + renderedItemId + "/content?format=" + renderedFormat))
                .content(exportedContent)
                .format(renderedFormat)
                .build();
        } catch (ClientException e) {
            logger.error("Failed to export file from SharePoint: {}", e.getMessage());
            throw new Exception("Failed to export file from SharePoint: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error while exporting file from SharePoint: {}", e.getMessage());
            throw e;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The ID of the exported item.",
            description = "The unique identifier of the exported file."
        )
        private final String itemId;

        @Schema(
            title = "The URI of the exported item.",
            description = "The Microsoft Graph API URI of the exported item."
        )
        private final URI uri;

        @Schema(
            title = "The content of the exported file.",
            description = "The content of the exported file in the specified format."
        )
        private final String content;

        @Schema(
            title = "The export format.",
            description = "The format the file was exported to."
        )
        private final String format;
    }
}
