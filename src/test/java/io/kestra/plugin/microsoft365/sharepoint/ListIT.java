package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.microsoft365.sharepoint.models.Item;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
@EnabledIf(
    value = "io.kestra.plugin.microsoft365.sharepoint.ListIT#shouldRunIntegrationTests",
    disabledReason = "Missing required environment variables: AZURE_TENANT_ID, AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, SHAREPOINT_SITE_ID, SHAREPOINT_DRIVE_ID"
)
class ListIT {
    private static final Logger log = LoggerFactory.getLogger(ListIT.class);

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
    void shouldListItemsInFolder() throws Exception {
        // Given - Create test items
        String folderName = "IT_ListTestFolder_" + System.currentTimeMillis();
        String fileName1 = "IT_ListTestFile1_" + System.currentTimeMillis() + ".txt";
        String fileName2 = "IT_ListTestFile2_" + System.currentTimeMillis() + ".json";

        // Create a test folder
        Create.Output folder = createFolder(parentId, folderName);
        createdItemIds.add(folder.getItemId());

        // Create test files in the folder
        Create.Output file1 = createFile(folder.getItemId(), fileName1, "Test content 1");
        createdItemIds.add(file1.getItemId());

        Create.Output file2 = createFile(folder.getItemId(), fileName2, "{\"test\": \"data\"}");
        createdItemIds.add(file2.getItemId());

        // When - List items in the folder
        List listTask = List.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .folderId(Property.ofValue(folder.getItemId()))
            .build();

        List.Output output = listTask.run(runContext);

        // Then
        assertThat(output.getItems(), hasSize(2));
        assertThat(output.getSize(), is(2));
        assertThat(output.getUri(), is(nullValue()));

        // Verify items are returned
        var itemNames = output.getItems().stream().map(Item::getName).toList();
        assertThat(itemNames, containsInAnyOrder(fileName1, fileName2));

        // Verify all items have required fields
        output.getItems().forEach(item -> {
            assertThat(item.getId(), notNullValue());
            assertThat(item.getName(), notNullValue());
            assertThat(item.getWebUrl(), notNullValue());
            assertThat(item.getIsFile(), is(true));
            assertThat(item.getIsFolder(), is(false));
        });
    }

    @Test
    void shouldListItemsInRoot() throws Exception {
        // Given - Create a test file in root
        String fileName = "IT_RootFile_" + System.currentTimeMillis() + ".txt";
        Create.Output file = createFile(parentId, fileName, "Root test content");
        createdItemIds.add(file.getItemId());

        // When
        List listTask = List.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .folderId(Property.ofValue(parentId))
            .build();

        List.Output output = listTask.run(runContext);

        // Then
        assertThat(output.getItems(), not(empty()));
        assertThat(output.getSize(), greaterThan(0));

        // Verify our created file is in the list
        var createdFile = output.getItems().stream()
            .filter(item -> item.getName().equals(fileName))
            .findFirst();
        assertThat(createdFile.isPresent(), is(true));
        assertThat(createdFile.get().getId(), is(file.getItemId()));
    }

    @Test
    void shouldReturnEmptyListForEmptyFolder() throws Exception {
        // Given - Create an empty folder
        String folderName = "IT_EmptyFolder_" + System.currentTimeMillis();
        Create.Output folder = createFolder(parentId, folderName);
        createdItemIds.add(folder.getItemId());

        // When
        List listTask = List.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .folderId(Property.ofValue(folder.getItemId()))
            .build();

        List.Output output = listTask.run(runContext);

        // Then
        assertThat(output.getItems(), empty());
        assertThat(output.getSize(), is(0));
    }

    @Test
    void shouldReturnOnlyFirstItemWithFetchOne() throws Exception {
        // Given - Create multiple test files
        String folderName = "IT_FetchOneFolder_" + System.currentTimeMillis();
        Create.Output folder = createFolder(parentId, folderName);
        createdItemIds.add(folder.getItemId());

        String fileName1 = "IT_First_" + System.currentTimeMillis() + ".txt";
        String fileName2 = "IT_Second_" + System.currentTimeMillis() + ".txt";

        Create.Output file1 = createFile(folder.getItemId(), fileName1, "First");
        createdItemIds.add(file1.getItemId());

        Create.Output file2 = createFile(folder.getItemId(), fileName2, "Second");
        createdItemIds.add(file2.getItemId());

        // When
        List listTask = List.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .folderId(Property.ofValue(folder.getItemId()))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        List.Output output = listTask.run(runContext);

        // Then
        assertThat(output.getItems(), hasSize(1));
        assertThat(output.getSize(), is(1));
        assertThat(output.getItem(), notNullValue());
        assertThat(output.getItem().getId(), notNullValue());
        assertThat(output.getUri(), is(nullValue()));
    }

    @Test
    void shouldStoreItemsToFile() throws Exception {
        // Given - Create test items
        String folderName = "IT_StoreFolder_" + System.currentTimeMillis();
        Create.Output folder = createFolder(parentId, folderName);
        createdItemIds.add(folder.getItemId());

        String fileName1 = "IT_StoreFile1_" + System.currentTimeMillis() + ".txt";
        String fileName2 = "IT_StoreFile2_" + System.currentTimeMillis() + ".txt";

        Create.Output file1 = createFile(folder.getItemId(), fileName1, "Content 1");
        createdItemIds.add(file1.getItemId());

        Create.Output file2 = createFile(folder.getItemId(), fileName2, "Content 2");
        createdItemIds.add(file2.getItemId());

        // When
        List listTask = List.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .folderId(Property.ofValue(folder.getItemId()))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        List.Output output = listTask.run(runContext);

        // Then
        assertThat(output.getItems(), is(nullValue()));
        assertThat(output.getSize(), is(2));
        assertThat(output.getUri(), notNullValue());

        // Verify the stored file contains the items
        var items = Flux.from(FileSerde.readAll(
            new BufferedReader(new InputStreamReader(runContext.storage().getFile(output.getUri())))
        )).collectList().block();

        assertThat(items, hasSize(2));
    }

    @Test
    void shouldReturnNoItemsWithFetchNone() throws Exception {
        // Given - Create test items
        String folderName = "IT_FetchNoneFolder_" + System.currentTimeMillis();
        Create.Output folder = createFolder(parentId, folderName);
        createdItemIds.add(folder.getItemId());

        String fileName = "IT_FetchNoneFile_" + System.currentTimeMillis() + ".txt";
        Create.Output file = createFile(folder.getItemId(), fileName, "Content");
        createdItemIds.add(file.getItemId());

        // When
        List listTask = List.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .folderId(Property.ofValue(folder.getItemId()))
            .fetchType(Property.ofValue(FetchType.NONE))
            .build();

        List.Output output = listTask.run(runContext);

        // Then
        assertThat(output.getItems(), empty());
        assertThat(output.getSize(), is(0));
        assertThat(output.getUri(), is(nullValue()));
    }

    @Test
    void shouldListMixedFilesAndFolders() throws Exception {
        // Given - Create mixed content
        String parentFolderName = "IT_MixedParent_" + System.currentTimeMillis();
        Create.Output parentFolder = createFolder(parentId, parentFolderName);
        createdItemIds.add(parentFolder.getItemId());

        String fileName = "IT_MixedFile_" + System.currentTimeMillis() + ".txt";
        String subFolderName = "IT_MixedSubFolder_" + System.currentTimeMillis();

        Create.Output file = createFile(parentFolder.getItemId(), fileName, "File content");
        createdItemIds.add(file.getItemId());

        Create.Output subFolder = createFolder(parentFolder.getItemId(), subFolderName);
        createdItemIds.add(subFolder.getItemId());

        // When
        List listTask = List.builder()
            .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
            .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
            .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
            .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
            .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
            .folderId(Property.ofValue(parentFolder.getItemId()))
            .build();

        List.Output output = listTask.run(runContext);

        // Then
        assertThat(output.getItems(), hasSize(2));
        assertThat(output.getSize(), is(2));

        // Verify we have one file and one folder
        long fileCount = output.getItems().stream().filter(Item::getIsFile).count();
        long folderCount = output.getItems().stream().filter(Item::getIsFolder).count();

        assertThat(fileCount, is(1L));
        assertThat(folderCount, is(1L));

        // Verify specific items
        var fileItem = output.getItems().stream()
            .filter(Item::getIsFile)
            .findFirst()
            .orElseThrow();
        assertThat(fileItem.getName(), is(fileName));

        var folderItem = output.getItems().stream()
            .filter(Item::getIsFolder)
            .findFirst()
            .orElseThrow();
        assertThat(folderItem.getName(), is(subFolderName));
    }

    // Helper methods to create test items
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
}