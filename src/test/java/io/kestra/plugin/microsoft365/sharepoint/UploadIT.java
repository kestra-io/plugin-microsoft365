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
    value = "io.kestra.plugin.microsoft365.sharepoint.UploadIT#shouldRunIntegrationTests",
    disabledReason = "Missing required environment variables: AZURE_TENANT_ID, AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, SHAREPOINT_SITE_ID, SHAREPOINT_DRIVE_ID"
)
class UploadIT {
    private static final Logger log = LoggerFactory.getLogger(UploadIT.class);

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

        // Initialize GraphServiceClient for cleanup
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
    void shouldUploadSmallTextFile() throws Exception {
        // Given - Create a small text file in storage
        String content = "This is a test file content for SharePoint upload.";
        String fileName = "IT_UploadTest_" + System.currentTimeMillis() + ".txt";
        URI fileUri = runContext.storage().putFile(
            new ByteArrayInputStream(content.getBytes()),
            fileName
        );

        // When - Upload to SharePoint
        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue(fileName))
            .parentId(Property.ofValue(parentId))
            .build();

        Upload.Output output = uploadTask.run(runContext);
        createdItemIds.add(output.getItemId());

        // Then
        assertThat(output.getItemId(), notNullValue());
        assertThat(output.getName(), is(fileName));
        assertThat(output.getWebUrl(), notNullValue());
        assertThat(output.getSize(), is((long) content.getBytes().length));
    }

    @Test
    void shouldUploadFileToRoot() throws Exception {
        // Given
        String content = "Root upload test content";
        String fileName = "IT_RootUpload_" + System.currentTimeMillis() + ".txt";
        URI fileUri = runContext.storage().putFile(
            new ByteArrayInputStream(content.getBytes()),
            fileName
        );

        // When
        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue(fileName))
            .parentId(Property.ofValue("root"))
            .build();

        Upload.Output output = uploadTask.run(runContext);
        createdItemIds.add(output.getItemId());

        // Then
        assertThat(output.getItemId(), notNullValue());
        assertThat(output.getName(), is(fileName));
        assertThat(output.getWebUrl(), notNullValue());
    }

    @Test
    void shouldUploadFileToSpecificFolder() throws Exception {
        // Given - Create a folder first
        String folderName = "IT_UploadFolder_" + System.currentTimeMillis();
        Create.Output folder = createFolder(parentId, folderName);
        createdItemIds.add(folder.getItemId());

        // Create file to upload
        String content = "File in specific folder";
        String fileName = "IT_FileInFolder_" + System.currentTimeMillis() + ".txt";
        URI fileUri = runContext.storage().putFile(
            new ByteArrayInputStream(content.getBytes()),
            fileName
        );

        // When - Upload to the specific folder
        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue(fileName))
            .parentId(Property.ofValue(folder.getItemId()))
            .build();

        Upload.Output output = uploadTask.run(runContext);
        createdItemIds.add(output.getItemId());

        // Then
        assertThat(output.getItemId(), notNullValue());
        assertThat(output.getName(), is(fileName));
        assertThat(output.getWebUrl(), containsString(folderName));
    }

    @Test
    void shouldUploadBinaryFile() throws Exception {
        // Given - Create a binary file (simulated PDF)
        byte[] binaryContent = new byte[1024];
        new Random().nextBytes(binaryContent);
        String fileName = "IT_BinaryFile_" + System.currentTimeMillis() + ".pdf";
        URI fileUri = runContext.storage().putFile(
            new ByteArrayInputStream(binaryContent),
            fileName
        );

        // When
        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue(fileName))
            .parentId(Property.ofValue(parentId))
            .build();

        Upload.Output output = uploadTask.run(runContext);
        createdItemIds.add(output.getItemId());

        // Then
        assertThat(output.getItemId(), notNullValue());
        assertThat(output.getName(), is(fileName));
        assertThat(output.getSize(), is((long) binaryContent.length));
    }

    @Test
    void shouldUploadLargeFile() throws Exception {
        // Given - Create a larger file (> 4MB to trigger chunked upload)
        // Using 6MB to ensure chunked upload is used
        int fileSize = 6 * 1024 * 1024;
        byte[] largeContent = new byte[fileSize];
        new Random().nextBytes(largeContent);

        String fileName = "IT_LargeFile_" + System.currentTimeMillis() + ".bin";
        URI fileUri = runContext.storage().putFile(
            new ByteArrayInputStream(largeContent),
            fileName
        );

        // When
        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue(fileName))
            .parentId(Property.ofValue(parentId))
            .build();

        Upload.Output output = uploadTask.run(runContext);
        createdItemIds.add(output.getItemId());

        // Then
        assertThat(output.getItemId(), notNullValue());
        assertThat(output.getName(), is(fileName));
        assertThat(output.getSize(), is((long) fileSize));
        assertThat(output.getWebUrl(), notNullValue());

        log.info("Successfully uploaded large file ({} MB)", fileSize / (1024 * 1024));
    }

    @Test
    void shouldUploadMultipleFilesSequentially() throws Exception {
        // Given - Multiple files to upload
        java.util.List<String> fileNames = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String content = "Content for file " + i;
            String fileName = "IT_MultiUpload_" + System.currentTimeMillis() + "_" + i + ".txt";
            fileNames.add(fileName);

            URI fileUri = runContext.storage().putFile(
                new ByteArrayInputStream(content.getBytes()),
                fileName
            );

            // When - Upload each file
            Upload uploadTask = Upload.builder()
                .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                .from(Property.ofValue(fileUri.toString()))
                .to(Property.ofValue(fileName))
                .parentId(Property.ofValue(parentId))
                .build();

            Upload.Output output = uploadTask.run(runContext);
            createdItemIds.add(output.getItemId());

            // Then
            assertThat(output.getItemId(), notNullValue());
            assertThat(output.getName(), is(fileName));
        }

        // Verify all files were uploaded
        assertThat(createdItemIds, hasSize(3));
    }

    @Test
    void shouldUploadJsonFile() throws Exception {
        // Given - JSON file
        String jsonContent = """
            {
                "test": "data",
                "timestamp": %d,
                "items": [1, 2, 3],
                "nested": {
                    "key": "value"
                }
            }
            """.formatted(System.currentTimeMillis());

        String fileName = "IT_JsonUpload_" + System.currentTimeMillis() + ".json";
        URI fileUri = runContext.storage().putFile(
            new ByteArrayInputStream(jsonContent.getBytes()),
            fileName
        );

        // When
        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue(fileName))
            .parentId(Property.ofValue(parentId))
            .build();

        Upload.Output output = uploadTask.run(runContext);
        createdItemIds.add(output.getItemId());

        // Then
        assertThat(output.getItemId(), notNullValue());
        assertThat(output.getName(), is(fileName));
        assertThat(output.getSize(), is((long) jsonContent.getBytes().length));
    }

    @Test
    void shouldUploadEmptyFile() throws Exception {
        // Given - Empty file
        String fileName = "IT_EmptyFile_" + System.currentTimeMillis() + ".txt";
        URI fileUri = runContext.storage().putFile(
            new ByteArrayInputStream(new byte[0]),
            fileName
        );

        // When
        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue(fileName))
            .parentId(Property.ofValue(parentId))
            .build();

        Upload.Output output = uploadTask.run(runContext);
        createdItemIds.add(output.getItemId());

        // Then
        assertThat(output.getItemId(), notNullValue());
        assertThat(output.getName(), is(fileName));
        assertThat(output.getSize(), is(0L));
    }

    @Test
    void shouldVerifyUploadedFileCanBeDownloaded() throws Exception {
        // Given - Upload a file
        String originalContent = "Content to verify download";
        String fileName = "IT_DownloadVerify_" + System.currentTimeMillis() + ".txt";
        URI fileUri = runContext.storage().putFile(
            new ByteArrayInputStream(originalContent.getBytes()),
            fileName
        );

        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue(fileName))
            .parentId(Property.ofValue(parentId))
            .build();

        Upload.Output uploadOutput = uploadTask.run(runContext);
        createdItemIds.add(uploadOutput.getItemId());

        // When - Download the file back
        InputStream downloadedStream = graphClient.drives()
            .byDriveId(driveId)
            .items()
            .byDriveItemId(uploadOutput.getItemId())
            .content()
            .get();

        String downloadedContent = new String(downloadedStream.readAllBytes());

        // Then
        assertThat(downloadedContent, is(originalContent));
    }

    // Helper method to create test folders
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