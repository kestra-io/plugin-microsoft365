package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
@EnabledIf(
    value = "io.kestra.plugin.microsoft365.sharepoint.ExportIT#shouldRunIntegrationTests",
    disabledReason = "Missing required environment variables: AZURE_TENANT_ID, AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, SHAREPOINT_SITE_ID, SHAREPOINT_DRIVE_ID"
)
class ExportIT {
    private static final Logger log = LoggerFactory.getLogger(ExportIT.class);

    @Inject
    private RunContextFactory runContextFactory;

    /**
     * Condition method to check if integration tests should run
     */
    static boolean shouldRunIntegrationTests() {
        return System.getenv("AZURE_TENANT_ID") != null &&
            System.getenv("AZURE_CLIENT_ID") != null &&
            System.getenv("AZURE_CLIENT_SECRET") != null &&
            System.getenv("SHAREPOINT_SITE_ID") != null &&
            System.getenv("SHAREPOINT_DRIVE_ID") != null;
    }


    @Test
    void shouldExportMarkdownToHtml() throws Exception {
        RunContext runContext = runContextFactory.of();
        java.util.List<String> createdItemIds = new ArrayList<>();

        try {
            // Given - Create a Markdown file
            String parentId = System.getenv().getOrDefault("SHAREPOINT_PARENT_ID", "root");
            String fileName = "IT_MdExport_" + System.currentTimeMillis() + ".md";
            String content = "# Heading\n\nThis is **bold** text and this is *italic* text.";

            Create.Output file = createFile(runContext, parentId, fileName, content);
            createdItemIds.add(file.getItemId());

        // When
        Export exportTask = Export.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(file.getItemId()))
            .format(Property.ofValue(Export.FormatType.valueOf("HTML")))
            .build();

        Export.Output output = exportTask.run(runContext);

        // Then
        assertThat(output.getOriginalName(), is(fileName));
        assertThat(output.getName(), is(fileName.replace(".md", ".html")));
        assertThat(output.getFormat(), is("html"));
        assertThat(output.getUri(), notNullValue());

            // Verify the HTML content was stored
            URI uri = new URI(output.getUri());
            try (InputStream stream = runContext.storage().getFile(uri)) {
                byte[] htmlContent = stream.readAllBytes();
                assertThat(htmlContent.length, greaterThan(0));
            }
        } finally {
            cleanup(runContext, createdItemIds);
        }
    }


    @Test
    void shouldExportRtfToPdf() throws Exception {
        RunContext runContext = runContextFactory.of();
        java.util.List<String> createdItemIds = new ArrayList<>();

        try {
            // Given - Create an RTF file
            String parentId = System.getenv().getOrDefault("SHAREPOINT_PARENT_ID", "root");
            String fileName = "IT_RtfExport_" + System.currentTimeMillis() + ".rtf";
            String content = "{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Times New Roman;}}\\f0\\fs60 Hello, World!}";

            Create.Output file = createFile(runContext, parentId, fileName, content);
            createdItemIds.add(file.getItemId());

        // When
        Export exportTask = Export.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(file.getItemId()))
            .format(Property.ofValue(Export.FormatType.valueOf("PDF")))
            .build();

        Export.Output output = exportTask.run(runContext);

            // Then
            assertThat(output.getOriginalName(), is(fileName));
            assertThat(output.getName(), is(fileName.replace(".rtf", ".pdf")));
            assertThat(output.getFormat(), is("pdf"));
            assertThat(output.getUri(), notNullValue());
        } finally {
            cleanup(runContext, createdItemIds);
        }
    }

    // Helper method to create test files
    private Create.Output createFile(RunContext runContext, String parentFolderId, String fileName, String content) throws Exception {
        Create createTask = Create.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .parentId(Property.ofValue(parentFolderId))
            .name(Property.ofValue(fileName))
            .itemType(Property.ofValue(Create.ItemType.FILE))
            .content(Property.ofValue(content))
            .build();

        return createTask.run(runContext);
    }

    private void cleanup(RunContext runContext, java.util.List<String> itemIds) {
        if (itemIds.isEmpty()) {
            return;
        }

        try {
            SharepointConnection connection = SharepointConnection.builder()
                .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                .build();

            GraphServiceClient graphClient = connection.createClient(runContext);
            String driveId = connection.getDriveId(runContext, graphClient);

            for (String itemId : itemIds) {
                try {
                    graphClient.drives()
                        .byDriveId(driveId)
                        .items()
                        .byDriveItemId(itemId)
                        .delete();
                    log.info("Deleted test item: {}", itemId);
                } catch (Exception e) {
                    log.warn("Failed to delete test item {}: {}", itemId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to initialize cleanup: {}", e.getMessage());
        }
    }
}
