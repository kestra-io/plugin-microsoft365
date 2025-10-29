package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.models.odataerrors.ODataError;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@EnabledIf(
    value = "io.kestra.plugin.microsoft365.sharepoint.DeleteIT#shouldRunIntegrationTests",
    disabledReason = "Missing required environment variables: AZURE_TENANT_ID, AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, SHAREPOINT_SITE_ID, SHAREPOINT_DRIVE_ID"
)
class DeleteIT {
    private static final Logger log = LoggerFactory.getLogger(DeleteIT.class);

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

        // Initialize GraphServiceClient for setup
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
    void shouldDeleteFile() throws Exception {
        // Given - Create a file to delete
        String fileName = "IT_DeleteFile_" + System.currentTimeMillis() + ".txt";
        String content = "File to be deleted";

        Create.Output file = createFile(parentId, fileName, content);
        String fileId = file.getItemId();

        // When - Delete the file
        Delete deleteTask = Delete.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(fileId))
            .build();

        Delete.Output output = deleteTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is(fileId));

        // Verify file no longer exists
        assertThrows(ODataError.class, () -> {
            graphClient.drives()
                .byDriveId(driveId)
                .items()
                .byDriveItemId(fileId)
                .get();
        });

        log.info("Successfully verified file deletion");
    }

    @Test
    void shouldDeleteFolder() throws Exception {
        // Given - Create a folder to delete
        String folderName = "IT_DeleteFolder_" + System.currentTimeMillis();

        Create.Output folder = createFolder(parentId, folderName);
        String folderId = folder.getItemId();

        // When - Delete the folder
        Delete deleteTask = Delete.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(folderId))
            .build();

        Delete.Output output = deleteTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is(folderId));

        // Verify folder no longer exists
        assertThrows(ODataError.class, () -> {
            graphClient.drives()
                .byDriveId(driveId)
                .items()
                .byDriveItemId(folderId)
                .get();
        });
    }

    @Test
    void shouldDeleteFolderWithContents() throws Exception {
        // Given - Create a folder with files inside
        String folderName = "IT_DeleteFolderWithFiles_" + System.currentTimeMillis();
        Create.Output folder = createFolder(parentId, folderName);
        String folderId = folder.getItemId();

        // Add files to the folder
        String fileName1 = "IT_FileInFolder1_" + System.currentTimeMillis() + ".txt";
        String fileName2 = "IT_FileInFolder2_" + System.currentTimeMillis() + ".txt";
        createFile(folderId, fileName1, "Content 1");
        createFile(folderId, fileName2, "Content 2");

        // When - Delete the folder (should delete contents too)
        Delete deleteTask = Delete.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(folderId))
            .build();

        Delete.Output output = deleteTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is(folderId));

        // Verify folder and all contents are deleted
        assertThrows(ODataError.class, () -> {
            graphClient.drives()
                .byDriveId(driveId)
                .items()
                .byDriveItemId(folderId)
                .get();
        });
    }

    @Test
    void shouldDeleteMultipleFilesSequentially() throws Exception {
        // Given - Create multiple files
        java.util.List<String> fileIds = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String fileName = "IT_MultiDelete_" + System.currentTimeMillis() + "_" + i + ".txt";
            Create.Output file = createFile(parentId, fileName, "Content " + i);
            fileIds.add(file.getItemId());
        }

        // When - Delete each file
        for (String fileId : fileIds) {
            Delete deleteTask = Delete.builder()
                .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                .itemId(Property.ofValue(fileId))
                .build();

            Delete.Output output = deleteTask.run(runContext);

            // Then
            assertThat(output.getItemId(), is(fileId));
        }

        // Verify all files are deleted
        for (String fileId : fileIds) {
            assertThrows(ODataError.class, () -> {
                graphClient.drives()
                    .byDriveId(driveId)
                    .items()
                    .byDriveItemId(fileId)
                    .get();
            });
        }
    }

    @Test
    void shouldDeleteFileFromSubfolder() throws Exception {
        // Given - Create a folder with a file
        String folderName = "IT_SubfolderDelete_" + System.currentTimeMillis();
        Create.Output folder = createFolder(parentId, folderName);
        createdItemIds.add(folder.getItemId());

        String fileName = "IT_FileInSubfolder_" + System.currentTimeMillis() + ".txt";
        Create.Output file = createFile(folder.getItemId(), fileName, "Content in subfolder");
        String fileId = file.getItemId();

        // When - Delete only the file
        Delete deleteTask = Delete.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(fileId))
            .build();

        Delete.Output output = deleteTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is(fileId));

        // Verify file is deleted
        assertThrows(ODataError.class, () -> {
            graphClient.drives()
                .byDriveId(driveId)
                .items()
                .byDriveItemId(fileId)
                .get();
        });

        // Verify folder still exists
        var folderStillExists = graphClient.drives()
            .byDriveId(driveId)
            .items()
            .byDriveItemId(folder.getItemId())
            .get();
        assertThat(folderStillExists, notNullValue());
        assertThat(folderStillExists.getId(), is(folder.getItemId()));
    }

    @Test
    void shouldDeleteLargeFile() throws Exception {
        // Given - Create and upload a large file
        int fileSize = 5 * 1024 * 1024; // 5MB
        byte[] largeContent = new byte[fileSize];
        new java.util.Random().nextBytes(largeContent);

        String fileName = "IT_DeleteLargeFile_" + System.currentTimeMillis() + ".bin";
        java.net.URI uploadUri = runContext.storage().putFile(
            new java.io.ByteArrayInputStream(largeContent),
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
        String fileId = uploadOutput.getItemId();

        // When - Delete the large file
        Delete deleteTask = Delete.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(fileId))
            .build();

        Delete.Output output = deleteTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is(fileId));

        // Verify deletion
        assertThrows(ODataError.class, () -> {
            graphClient.drives()
                .byDriveId(driveId)
                .items()
                .byDriveItemId(fileId)
                .get();
        });

        log.info("Successfully deleted large file ({} MB)", fileSize / (1024 * 1024));
    }

    @Test
    void shouldFailWhenDeletingNonExistentItem() throws Exception {
        // Given - A non-existent item ID
        String nonExistentId = "non-existent-item-id-" + System.currentTimeMillis();

        // When/Then - Should throw an error
        Delete deleteTask = Delete.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(nonExistentId))
            .build();

        assertThrows(Exception.class, () -> deleteTask.run(runContext));
    }

    @Test
    void shouldDeleteEmptyFile() throws Exception {
        // Given - Create an empty file
        String fileName = "IT_DeleteEmptyFile_" + System.currentTimeMillis() + ".txt";
        Create.Output file = createFile(parentId, fileName, "");
        String fileId = file.getItemId();

        // When - Delete the empty file
        Delete deleteTask = Delete.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(fileId))
            .build();

        Delete.Output output = deleteTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is(fileId));

        // Verify deletion
        assertThrows(ODataError.class, () -> {
            graphClient.drives()
                .byDriveId(driveId)
                .items()
                .byDriveItemId(fileId)
                .get();
        });
    }

    @Test
    void shouldDeleteNestedFolderStructure() throws Exception {
        // Given - Create nested folder structure
        String parentFolderName = "IT_ParentFolder_" + System.currentTimeMillis();
        Create.Output parentFolder = createFolder(parentId, parentFolderName);
        String parentFolderId = parentFolder.getItemId();

        String childFolderName = "IT_ChildFolder_" + System.currentTimeMillis();
        Create.Output childFolder = createFolder(parentFolderId, childFolderName);

        String fileName = "IT_NestedFile_" + System.currentTimeMillis() + ".txt";
        createFile(childFolder.getItemId(), fileName, "Nested content");

        // When - Delete parent folder (should delete all nested items)
        Delete deleteTask = Delete.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(parentFolderId))
            .build();

        Delete.Output output = deleteTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is(parentFolderId));

        // Verify entire structure is deleted
        assertThrows(ODataError.class, () -> {
            graphClient.drives()
                .byDriveId(driveId)
                .items()
                .byDriveItemId(parentFolderId)
                .get();
        });
    }

    @Test
    void shouldDeleteFileWithSpecialCharacters() throws Exception {
        // Given - Create file with special characters
        String fileName = "IT_Special-File_Name_" + System.currentTimeMillis() + " (1).txt";
        Create.Output file = createFile(parentId, fileName, "Special content");
        String fileId = file.getItemId();

        // When - Delete the file
        Delete deleteTask = Delete.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .itemId(Property.ofValue(fileId))
            .build();

        Delete.Output output = deleteTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is(fileId));

        // Verify deletion
        assertThrows(ODataError.class, () -> {
            graphClient.drives()
                .byDriveId(driveId)
                .items()
                .byDriveItemId(fileId)
                .get();
        });
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