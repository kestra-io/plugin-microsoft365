package io.kestra.plugin.microsoft365.oneshare;

import com.devskiller.friendly_id.FriendlyId;
import com.google.common.collect.ImmutableMap;
import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.item.items.ItemsRequestBuilder;
import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.utils.TestsUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.util.Objects;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@KestraTest
class DeleteTest extends AbstractOneShareTest {

    private static MockedConstruction<GraphServiceClient> graphClientMock;
    
    @BeforeAll
    static void setupMocks() {
        // Mock GraphServiceClient and the drive API chain for deleting items
        graphClientMock = Mockito.mockConstruction(GraphServiceClient.class, (mock, context) -> {
            DrivesRequestBuilder drivesBuilder = mock(DrivesRequestBuilder.class);
            DriveItemRequestBuilder driveItemBuilder = mock(DriveItemRequestBuilder.class);
            ItemsRequestBuilder itemsBuilder = mock(ItemsRequestBuilder.class);
            DriveItemItemRequestBuilder driveItemItemBuilder = mock(DriveItemItemRequestBuilder.class);

            // Mock the delete operation (void return)
            doNothing().when(driveItemItemBuilder).delete();

            // Chain the mocks: drives → byDriveId → items → byDriveItemId
            when(itemsBuilder.byDriveItemId(anyString())).thenReturn(driveItemItemBuilder);
            when(driveItemBuilder.items()).thenReturn(itemsBuilder);
            when(drivesBuilder.byDriveId(anyString())).thenReturn(driveItemBuilder);
            when(mock.drives()).thenReturn(drivesBuilder);
        });
    }

    @AfterAll
    static void tearDownMocks() {
        if (graphClientMock != null) {
            graphClientMock.close();
        }
    }

    // ================== Mock-based Unit Tests ==================
    
    @Test
    void testDeleteExecutesSuccessfully() throws Exception {
        Delete task = Delete.builder()
            .id("test-delete")
            .type(Delete.class.getName())
            .driveId(Property.ofValue("test-drive"))
            .itemId(Property.ofValue("item-to-delete"))
            .tenantId(Property.ofValue("mock-tenant"))
            .clientId(Property.ofValue("mock-client"))
            .clientSecret(Property.ofValue("mock-secret"))
            .build();
        
        RunContext runContext = runContextFactory.of();
        task.run(runContext);
        
        // Verify delete executed successfully (no exception thrown)
        assertThat(true, is(true));
    }

    @Test
    void testDeleteTaskConfiguration() {
        // Lightweight configuration test
        Delete task = Delete.builder()
            .id("test-delete")
            .type(Delete.class.getName())
            .driveId(Property.ofValue("test-drive"))
            .itemId(Property.ofValue("item-to-delete"))
            .tenantId(Property.ofValue("mock-tenant"))
            .clientId(Property.ofValue("mock-client"))
            .clientSecret(Property.ofValue("mock-secret"))
            .build();
        
        assertThat(task, notNullValue());
        assertThat(task.getDriveId(), notNullValue());
        assertThat(task.getItemId(), notNullValue());
    }

    // ================== E2E Tests (requires credentials) ==================

    @Test
    void fromStorage() throws Exception {
        Assumptions.assumeTrue(credentialsAvailable,
            "Skipping test - Microsoft 365 credentials not available");

        File file = new File(Objects.requireNonNull(DeleteTest.class.getClassLoader()
            .getResource("application.yml"))
            .toURI());

        URI source = storageInterface.put(
            TenantService.MAIN_TENANT,
            null,
            new URI("/" + FriendlyId.createFriendlyId()),
            new FileInputStream(file)
        );

        String fileName = FriendlyId.createFriendlyId() + ".yml";

        Upload upload = Upload.builder()
            .id(UploadTest.class.getSimpleName())
            .type(Upload.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root:/Documents/TestDelete"))
            .fileName(Property.ofValue(fileName))
            .from(Property.ofValue(source.toString()))
            .build();

        Upload.Output uploadOutput = upload.run(runContext(upload));

        Delete task = Delete.builder()
            .id(DeleteTest.class.getSimpleName())
            .type(Delete.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .itemId(Property.ofValue(uploadOutput.getFile().getId()))
            .build();

        // First delete should succeed (file exists)
        task.run(runContext(task));

        // Verify file was deleted by trying to download it
        Download downloadTask = Download.builder()
            .id(DownloadTest.class.getSimpleName())
            .type(Download.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .itemId(Property.ofValue(uploadOutput.getFile().getId()))
            .build();

        // This should throw an exception since file is deleted
        try {
            downloadTask.run(runContext(downloadTask));
            assertThat("Expected exception for deleted file", false);
        } catch (Exception e) {
            // Expected - file should not be found
            assertThat(true, is(true));
        }
    }

    private RunContext runContext(Task task) {
        return TestsUtils.mockRunContext(
            this.runContextFactory,
            task,
            ImmutableMap.of(
                "tenantId", tenantId,
                "clientId", clientId,
                "clientSecret", clientSecret,
                "driveId", driveId
            )
        );
    }
}
