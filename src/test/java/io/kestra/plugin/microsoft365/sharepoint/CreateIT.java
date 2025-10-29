package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
@EnabledIf(
    value = "io.kestra.plugin.microsoft365.sharepoint.CreateIT#shouldRunIntegrationTests",
    disabledReason = "Missing required environment variables: AZURE_TENANT_ID, AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, SHAREPOINT_SITE_ID, SHAREPOINT_DRIVE_ID"
)
class CreateIT {
    private static final Logger log = LoggerFactory.getLogger(CreateIT.class);

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
    void shouldCreateFolder() throws Exception {
        RunContext runContext = runContextFactory.of();
        List<String> createdItemIds = new ArrayList<>();

        try {
            // Given
            String folderName = "IT_TestFolder_" + System.currentTimeMillis();
            String parentId = System.getenv().getOrDefault("SHAREPOINT_PARENT_ID", "root");

            Create createTask = Create.builder()
                .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                .parentId(Property.ofValue(parentId))
                .name(Property.ofValue(folderName))
                .itemType(Property.ofValue(Create.ItemType.FOLDER))
                .build();

            // When
            Create.Output output = createTask.run(runContext);
            createdItemIds.add(output.getItemId());

            // Then
            assertThat(output.getItemId(), notNullValue());
            assertThat(output.getItemName(), is(folderName));
            assertThat(output.getWebUrl(), notNullValue());
            assertThat(output.getWebUrl(), containsString(folderName));
        } finally {
            cleanup(runContext, createdItemIds);
        }
    }

    @Test
    void shouldCreateFileWithContent() throws Exception {
        RunContext runContext = runContextFactory.of();
        List<String> createdItemIds = new ArrayList<>();

        try {
            // Given
            String fileName = "IT_TestFile_" + System.currentTimeMillis() + ".txt";
            String fileContent = "Integration test content - created at " + System.currentTimeMillis();
            String parentId = System.getenv().getOrDefault("SHAREPOINT_PARENT_ID", "root");

            Create createTask = Create.builder()
                .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                .parentId(Property.ofValue(parentId))
                .name(Property.ofValue(fileName))
                .itemType(Property.ofValue(Create.ItemType.FILE))
                .content(Property.ofValue(fileContent))
                .build();

            // When
            Create.Output output = createTask.run(runContext);
            createdItemIds.add(output.getItemId());

            // Then
            assertThat(output.getItemId(), notNullValue());
            assertThat(output.getItemName(), is(fileName));
            assertThat(output.getWebUrl(), notNullValue());
            assertThat(output.getWebUrl(), containsString(fileName));
        } finally {
            cleanup(runContext, createdItemIds);
        }
    }

    @Test
    void shouldCreateEmptyFile() throws Exception {
        RunContext runContext = runContextFactory.of();
        List<String> createdItemIds = new ArrayList<>();

        try {
            // Given
            String fileName = "IT_EmptyFile_" + System.currentTimeMillis() + ".txt";
            String parentId = System.getenv().getOrDefault("SHAREPOINT_PARENT_ID", "root");

            Create createTask = Create.builder()
                .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                .parentId(Property.ofValue(parentId))
                .name(Property.ofValue(fileName))
                .itemType(Property.ofValue(Create.ItemType.FILE))
                .build();

            // When
            Create.Output output = createTask.run(runContext);
            createdItemIds.add(output.getItemId());

            // Then
            assertThat(output.getItemId(), notNullValue());
            assertThat(output.getItemName(), is(fileName));
            assertThat(output.getWebUrl(), notNullValue());
        } finally {
            cleanup(runContext, createdItemIds);
        }
    }

    @Test
    void shouldCreateFileWithSpecialCharactersInName() throws Exception {
        RunContext runContext = runContextFactory.of();
        List<String> createdItemIds = new ArrayList<>();

        try {
            // Given
            String fileName = "IT_Special File-Name_" + System.currentTimeMillis() + ".txt";
            String parentId = System.getenv().getOrDefault("SHAREPOINT_PARENT_ID", "root");

            Create createTask = Create.builder()
                .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                .parentId(Property.ofValue(parentId))
                .name(Property.ofValue(fileName))
                .itemType(Property.ofValue(Create.ItemType.FILE))
                .content(Property.ofValue("Test content"))
                .build();

            // When
            Create.Output output = createTask.run(runContext);
            createdItemIds.add(output.getItemId());

            // Then
            assertThat(output.getItemId(), notNullValue());
            assertThat(output.getItemName(), is(fileName));
        } finally {
            cleanup(runContext, createdItemIds);
        }
    }

    @Test
    void shouldCreateFileWithDifferentExtensions() throws Exception {
        RunContext runContext = runContextFactory.of();
        List<String> createdItemIds = new ArrayList<>();

        try {
            // Given - Test with JSON file
            String fileName = "IT_TestData_" + System.currentTimeMillis() + ".json";
            String jsonContent = "{\"test\": \"data\", \"timestamp\": " + System.currentTimeMillis() + "}";
            String parentId = System.getenv().getOrDefault("SHAREPOINT_PARENT_ID", "root");

            Create createTask = Create.builder()
                .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                .parentId(Property.ofValue(parentId))
                .name(Property.ofValue(fileName))
                .itemType(Property.ofValue(Create.ItemType.FILE))
                .content(Property.ofValue(jsonContent))
                .build();

            // When
            Create.Output output = createTask.run(runContext);
            createdItemIds.add(output.getItemId());

            // Then
            assertThat(output.getItemId(), notNullValue());
            assertThat(output.getItemName(), is(fileName));
            assertThat(output.getWebUrl(), containsString(".json"));
        } finally {
            cleanup(runContext, createdItemIds);
        }
    }

    private void cleanup(RunContext runContext, List<String> itemIds) {
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