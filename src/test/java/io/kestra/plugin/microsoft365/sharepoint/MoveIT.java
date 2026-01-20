package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemCollectionResponse;
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
import java.util.Objects;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
@EnabledIf(
    value = "io.kestra.plugin.microsoft365.sharepoint.MoveIT#shouldRunIntegrationTests",
    disabledReason = "Missing required environment variables: AZURE_TENANT_ID, AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, SHAREPOINT_SITE_ID, SHAREPOINT_DRIVE_ID"
)
class MoveIT {
    private static final Logger log = LoggerFactory.getLogger(MoveIT.class);

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
    void shouldMoveFile() throws Exception {
        RunContext runContext = runContextFactory.of();
        List<String> createdItemIds = new ArrayList<>();

        try {
            // Given
            String parentId = System.getenv().getOrDefault("SHAREPOINT_PARENT_ID", "root");
            String destParentId = System.getenv().getOrDefault("SHAREPOINT_DEST_PARENT_ID", parentId);

            // Ensure destination folder exists (create one if not provided)
            if (Objects.equals(destParentId, parentId)) {
                String destFolderName = "IT_MoveDest_" + System.currentTimeMillis();
                Create destFolderTask = Create.builder()
                    .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                    .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                    .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                    .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                    .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                    .parentId(Property.ofValue(parentId))
                    .name(Property.ofValue(destFolderName))
                    .itemType(Property.ofValue(Create.ItemType.FOLDER))
                    .build();

                Create.Output destFolderOut = destFolderTask.run(runContext);
                createdItemIds.add(destFolderOut.getItemId());
                destParentId = destFolderOut.getItemId();
            }

            // Create a file to move
            String fileName = "IT_MoveFile_" + System.currentTimeMillis() + ".txt";
            Create createFileTask = Create.builder()
                .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                .parentId(Property.ofValue(parentId))
                .name(Property.ofValue(fileName))
                .itemType(Property.ofValue(Create.ItemType.FILE))
                .content(Property.ofValue("MoveIT content - " + System.currentTimeMillis()))
                .build();

            Create.Output createdFile = createFileTask.run(runContext);
            createdItemIds.add(createdFile.getItemId());

            // When - Move the file
            Move moveTask = Move.builder()
                .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                .itemId(Property.ofValue(createdFile.getItemId()))
                .destinationParentId(Property.ofValue(destParentId))
                .build();

            Move.Output moved = moveTask.run(runContext);

            // Then
            assertThat(moved.getItemId(), notNullValue());
            assertThat(moved.getItemId(), is(createdFile.getItemId()));
            assertThat(moved.getItemName(), is(fileName));
            assertThat(moved.getParentId(), is(destParentId));
            assertThat(moved.getWebUrl(), notNullValue());

            // Verify by listing destination folder and checking the item is present
            assertThat(listChildNames(runContext, destParentId), hasItem(fileName));

            // (Optional) Best-effort verify it is NOT in the source folder anymore
            // Note: SharePoint indexing/consistency can be eventual; keep this best-effort.
            assertThat(listChildNames(runContext, parentId), not(hasItem(fileName)));
        } finally {
            cleanup(runContext, createdItemIds);
        }
    }

    @Test
    void shouldMoveAndRenameFile() throws Exception {
        RunContext runContext = runContextFactory.of();
        List<String> createdItemIds = new ArrayList<>();

        try {
            // Given
            String parentId = System.getenv().getOrDefault("SHAREPOINT_PARENT_ID", "root");

            // Create a destination folder for this test
            String destFolderName = "IT_MoveRenameDest_" + System.currentTimeMillis();
            Create destFolderTask = Create.builder()
                .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                .parentId(Property.ofValue(parentId))
                .name(Property.ofValue(destFolderName))
                .itemType(Property.ofValue(Create.ItemType.FOLDER))
                .build();

            Create.Output destFolderOut = destFolderTask.run(runContext);
            createdItemIds.add(destFolderOut.getItemId());
            String destParentId = destFolderOut.getItemId();

            // Create a file to move
            String fileName = "IT_MoveRenameFile_" + System.currentTimeMillis() + ".txt";
            Create createFileTask = Create.builder()
                .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                .parentId(Property.ofValue(parentId))
                .name(Property.ofValue(fileName))
                .itemType(Property.ofValue(Create.ItemType.FILE))
                .content(Property.ofValue("Move+Rename IT content - " + System.currentTimeMillis()))
                .build();

            Create.Output createdFile = createFileTask.run(runContext);
            createdItemIds.add(createdFile.getItemId());

            String newName = "IT_Renamed_" + System.currentTimeMillis() + ".txt";

            // When - Move and rename the file
            Move moveTask = Move.builder()
                .tenantId(Property.ofValue(System.getenv("AZURE_TENANT_ID")))
                .clientId(Property.ofValue(System.getenv("AZURE_CLIENT_ID")))
                .clientSecret(Property.ofValue(System.getenv("AZURE_CLIENT_SECRET")))
                .siteId(Property.ofValue(System.getenv("SHAREPOINT_SITE_ID")))
                .driveId(Property.ofValue(System.getenv("SHAREPOINT_DRIVE_ID")))
                .itemId(Property.ofValue(createdFile.getItemId()))
                .destinationParentId(Property.ofValue(destParentId))
                .newName(Property.ofValue(newName))
                .build();

            Move.Output moved = moveTask.run(runContext);

            // Then
            assertThat(moved.getItemId(), is(createdFile.getItemId()));
            assertThat(moved.getItemName(), is(newName));
            assertThat(moved.getParentId(), is(destParentId));
            assertThat(moved.getWebUrl(), notNullValue());
            assertThat(moved.getWebUrl(), containsString(newName));

            // Verify by listing destination folder and checking the renamed item is present
            assertThat(listChildNames(runContext, destParentId), hasItem(newName));

            // Best-effort verify old name not present in destination
            assertThat(listChildNames(runContext, destParentId), not(hasItem(fileName)));
        } finally {
            cleanup(runContext, createdItemIds);
        }
    }

    private List<String> listChildNames(RunContext runContext, String folderId) {
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

            DriveItemCollectionResponse childrenResp = graphClient
                .drives()
                .byDriveId(driveId)
                .items()
                .byDriveItemId(folderId)
                .children()
                .get();

            return Objects.requireNonNullElse(childrenResp.getValue(), List.<DriveItem>of()).stream()
                .map(DriveItem::getName)
                .filter(Objects::nonNull)
                .toList();
        } catch (Exception e) {
            log.warn("Failed to list children for folder {}: {}", folderId, e.getMessage());
            return List.of();
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
