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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
@EnabledIf(
    value = "io.kestra.plugin.microsoft365.sharepoint.DownloadIT#shouldRunIntegrationTests",
    disabledReason = "Missing required environment variables: AZURE_TENANT_ID, AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, SHAREPOINT_SITE_ID, SHAREPOINT_DRIVE_ID"
)
class DownloadIT {
    private static final Logger log = LoggerFactory.getLogger(DownloadIT.class);

    @Inject
    private RunContextFactory runContextFactory;

    private RunContext runContext;
    private java.util.List<String> createdItemIds;
    private GraphServiceClient graphClient;
    private String driveId;
    private String parentId;

    @BeforeEach
    void setUp() throws Exception {
        runContext = runContextFactory.of();
        createdItemIds = new ArrayList<>();

        // Initialize GraphServiceClient for setup and cleanup
        SharepointConnection connection = SharepointConnection.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .build();

        graphClient = connection.createClient(runContext);
        driveId = connection.getDriveId(runContext, graphClient);
        parentId = System.getenv().getOrDefault("SHAREPOINT_PARENT_ID", "root");
    }

    @AfterEach
    void tearDown() {
        // Clean up all created items
        for (String itemId : createdItemIds) {
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
        createdItemIds.clear();
    }

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
    void shouldDownloadFileByItemId() throws Exception {
        // Given - Create a file to download
        String originalContent = "This is test content for download test.";
        String fileName = "IT_DownloadTest_" + System.currentTimeMillis() + ".txt";

        Create.Output file = createFile(parentId, fileName, originalContent);
        createdItemIds.add(file.getItemId());

        // When - Download by item ID
        Download downloadTask = Download.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(file.getItemId()))
            .build();

        Download.Output output = downloadTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is(file.getItemId()));
        assertThat(output.getName(), is(fileName));
        assertThat(output.getUri(), notNullValue());
        assertThat(output.getSize(), notNullValue());
        assertThat(output.getWebUrl(), notNullValue());

        // Verify downloaded content matches original
        URI downloadedUri = new URI(output.getUri());
        try (InputStream stream = runContext.storage().getFile(downloadedUri)) {
            String downloadedContent = new String(stream.readAllBytes());
            assertThat(downloadedContent, is(originalContent));
        }
    }

    @Test
    void shouldDownloadFileByPath() throws Exception {
        // Given - Create a file to download
        String originalContent = "Content for path-based download test.";
        String fileName = "IT_PathDownload_" + System.currentTimeMillis() + ".txt";

        Create.Output file = createFile(parentId, fileName, originalContent);
        createdItemIds.add(file.getItemId());

        // Construct item path (assuming root or simple path)
        String itemPath = "/" + fileName;
        if (!parentId.equals("root")) {
            // For non-root, this is simplified - in real scenarios you'd need full path
            itemPath = "/" + fileName;
        }

        // When - Download by path
        Download downloadTask = Download.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemPath(Property.ofValue(itemPath))
            .build();

        Download.Output output = downloadTask.run(runContext);

        // Then
        assertThat(output.getName(), is(fileName));
        assertThat(output.getUri(), notNullValue());
        assertThat(output.getSize(), notNullValue());

        // Verify content
        URI downloadedUri = new URI(output.getUri());
        try (InputStream stream = runContext.storage().getFile(downloadedUri)) {
            String downloadedContent = new String(stream.readAllBytes());
            assertThat(downloadedContent, is(originalContent));
        }
    }

    @Test
    void shouldDownloadTextFile() throws Exception {
        // Given
        String originalContent = "Plain text file content with multiple lines.\nLine 2\nLine 3";
        String fileName = "IT_TextDownload_" + System.currentTimeMillis() + ".txt";

        Create.Output file = createFile(parentId, fileName, originalContent);
        createdItemIds.add(file.getItemId());

        // When
        Download downloadTask = Download.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(file.getItemId()))
            .build();

        Download.Output output = downloadTask.run(runContext);

        // Then
        assertThat(output.getName(), is(fileName));
        assertThat(output.getSize(), is((long) originalContent.getBytes().length));

        URI downloadedUri = new URI(output.getUri());
        try (InputStream stream = runContext.storage().getFile(downloadedUri)) {
            String content = new String(stream.readAllBytes());
            assertThat(content, is(originalContent));
        }
    }

    @Test
    void shouldDownloadJsonFile() throws Exception {
        // Given - JSON file
        String jsonContent = """
            {
                "id": 1,
                "name": "Test Item",
                "values": [1, 2, 3, 4, 5],
                "nested": {
                    "key": "value"
                }
            }
            """;
        String fileName = "IT_JsonDownload_" + System.currentTimeMillis() + ".json";

        Create.Output file = createFile(parentId, fileName, jsonContent);
        createdItemIds.add(file.getItemId());

        // When
        Download downloadTask = Download.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(file.getItemId()))
            .build();

        Download.Output output = downloadTask.run(runContext);

        // Then
        assertThat(output.getName(), endsWith(".json"));

        URI downloadedUri = new URI(output.getUri());
        try (InputStream stream = runContext.storage().getFile(downloadedUri)) {
            String content = new String(stream.readAllBytes());
            assertThat(content, is(jsonContent));
        }
    }

    @Test
    void shouldDownloadBinaryFile() throws Exception {
        // Given - Binary file
        byte[] binaryContent = new byte[2048];
        new Random().nextBytes(binaryContent);
        String fileName = "IT_BinaryDownload_" + System.currentTimeMillis() + ".bin";

        // Upload binary file first
        URI uploadUri = runContext.storage().putFile(
            new ByteArrayInputStream(binaryContent),
            fileName
        );

        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .from(Property.ofValue(uploadUri.toString()))
            .to(Property.ofValue(fileName))
            .parentId(Property.ofValue(parentId))
            .build();

        Upload.Output uploadOutput = uploadTask.run(runContext);
        createdItemIds.add(uploadOutput.getItemId());

        // When - Download it back
        Download downloadTask = Download.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(uploadOutput.getItemId()))
            .build();

        Download.Output output = downloadTask.run(runContext);

        // Then
        assertThat(output.getName(), is(fileName));
        assertThat(output.getSize(), is((long) binaryContent.length));

        // Verify binary content matches
        URI downloadedUri = new URI(output.getUri());
        try (InputStream stream = runContext.storage().getFile(downloadedUri)) {
            byte[] downloadedContent = stream.readAllBytes();
            assertThat(downloadedContent, is(binaryContent));
        }
    }

    @Test
    void shouldDownloadLargeFile() throws Exception {
        // Given - Large file (5MB)
        int fileSize = 5 * 1024 * 1024;
        byte[] largeContent = new byte[fileSize];
        new Random().nextBytes(largeContent);
        String fileName = "IT_LargeDownload_" + System.currentTimeMillis() + ".dat";

        // Upload large file first
        URI uploadUri = runContext.storage().putFile(
            new ByteArrayInputStream(largeContent),
            fileName
        );

        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .from(Property.ofValue(uploadUri.toString()))
            .to(Property.ofValue(fileName))
            .parentId(Property.ofValue(parentId))
            .build();

        Upload.Output uploadOutput = uploadTask.run(runContext);
        createdItemIds.add(uploadOutput.getItemId());

        // When - Download it back
        Download downloadTask = Download.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(uploadOutput.getItemId()))
            .build();

        Download.Output output = downloadTask.run(runContext);

        // Then
        assertThat(output.getName(), is(fileName));
        assertThat(output.getSize(), is((long) fileSize));

        // Verify file was downloaded
        URI downloadedUri = new URI(output.getUri());
        try (InputStream stream = runContext.storage().getFile(downloadedUri)) {
            byte[] downloadedContent = stream.readAllBytes();
            assertThat(downloadedContent.length, is(fileSize));
        }

        log.info("Successfully downloaded large file ({} MB)", fileSize / (1024 * 1024));
    }

    @Test
    void shouldDownloadFileFromFolder() throws Exception {
        // Given - Create a folder and file inside it
        String folderName = "IT_DownloadFolder_" + System.currentTimeMillis();
        Create.Output folder = createFolder(parentId, folderName);
        createdItemIds.add(folder.getItemId());

        String originalContent = "Content in subfolder";
        String fileName = "IT_SubfolderFile_" + System.currentTimeMillis() + ".txt";
        Create.Output file = createFile(folder.getItemId(), fileName, originalContent);
        createdItemIds.add(file.getItemId());

        // When - Download file from folder
        Download downloadTask = Download.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(file.getItemId()))
            .build();

        Download.Output output = downloadTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is(file.getItemId()));
        assertThat(output.getName(), is(fileName));

        URI downloadedUri = new URI(output.getUri());
        try (InputStream stream = runContext.storage().getFile(downloadedUri)) {
            String content = new String(stream.readAllBytes());
            assertThat(content, is(originalContent));
        }
    }

    @Test
    void shouldDownloadMultipleFilesSequentially() throws Exception {
        // Given - Create multiple files
        java.util.List<Create.Output> files = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String content = "Content for file " + i;
            String fileName = "IT_MultiDownload_" + System.currentTimeMillis() + "_" + i + ".txt";
            Create.Output file = createFile(parentId, fileName, content);
            createdItemIds.add(file.getItemId());
            files.add(file);
        }

        // When - Download each file
        for (int i = 0; i < files.size(); i++) {
            Create.Output file = files.get(i);

            Download downloadTask = Download.builder()
                .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                .itemId(Property.ofValue(file.getItemId()))
                .build();

            Download.Output output = downloadTask.run(runContext);

            // Then
            assertThat(output.getItemId(), is(file.getItemId()));
            assertThat(output.getName(), is(file.getItemName()));

            // Verify content
            URI downloadedUri = new URI(output.getUri());
            try (InputStream stream = runContext.storage().getFile(downloadedUri)) {
                String content = new String(stream.readAllBytes());
                assertThat(content, is("Content for file " + (i + 1)));
            }
        }
    }

    @Test
    void shouldDownloadEmptyFile() throws Exception {
        // Given - Empty file
        String fileName = "IT_EmptyDownload_" + System.currentTimeMillis() + ".txt";
        Create.Output file = createFile(parentId, fileName, "");
        createdItemIds.add(file.getItemId());

        // When
        Download downloadTask = Download.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(file.getItemId()))
            .build();

        Download.Output output = downloadTask.run(runContext);

        // Then
        assertThat(output.getName(), is(fileName));
        assertThat(output.getSize(), is(0L));

        URI downloadedUri = new URI(output.getUri());
        try (InputStream stream = runContext.storage().getFile(downloadedUri)) {
            byte[] content = stream.readAllBytes();
            assertThat(content.length, is(0));
        }
    }

    @Test
    void shouldPreserveFileMetadata() throws Exception {
        // Given
        String originalContent = "Metadata test content";
        String fileName = "IT_MetadataDownload_" + System.currentTimeMillis() + ".txt";
        Create.Output file = createFile(parentId, fileName, originalContent);
        createdItemIds.add(file.getItemId());

        // When
        Download downloadTask = Download.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(file.getItemId()))
            .build();

        Download.Output output = downloadTask.run(runContext);

        // Then - Verify all metadata is present
        assertThat(output.getItemId(), is(file.getItemId()));
        assertThat(output.getName(), is(fileName));
        assertThat(output.getWebUrl(), is(file.getWebUrl()));
        assertThat(output.getSize(), notNullValue());
        assertThat(output.getUri(), notNullValue());
    }

    // Helper methods
    private Create.Output createFile(String parentFolderId, String fileName, String content) throws Exception {
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

    private Create.Output createFolder(String parentFolderId, String folderName) throws Exception {
        Create createTask = Create.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .parentId(Property.ofValue(parentFolderId))
            .name(Property.ofValue(folderName))
            .itemType(Property.ofValue(Create.ItemType.FOLDER))
            .build();

        return createTask.run(runContext);
    }
}