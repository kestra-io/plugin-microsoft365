package io.kestra.plugin.microsoft365.oneshare;

import com.devskiller.friendly_id.FriendlyId;
import com.google.common.collect.ImmutableMap;
import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.item.items.ItemsRequestBuilder;
import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.drives.item.items.item.content.ContentRequestBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.utils.TestsUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static io.kestra.core.tenant.TenantService.MAIN_TENANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@KestraTest
class ExportTest extends AbstractOneShareTest {
    @Inject
    private OnesShareTestUtils testUtils;

    private static MockedConstruction<GraphServiceClient> graphClientMock;
    
    @BeforeAll
    static void setupMocks() {
        // Mock GraphServiceClient and the drive API chain for exporting files
        graphClientMock = Mockito.mockConstruction(GraphServiceClient.class, (mock, context) -> {
            DrivesRequestBuilder drivesBuilder = mock(DrivesRequestBuilder.class);
            DriveItemRequestBuilder driveItemBuilder = mock(DriveItemRequestBuilder.class);
            ItemsRequestBuilder itemsBuilder = mock(ItemsRequestBuilder.class);
            DriveItemItemRequestBuilder driveItemItemBuilder = mock(DriveItemItemRequestBuilder.class);
            ContentRequestBuilder contentBuilder = mock(ContentRequestBuilder.class);

            // Mock the exported file content (PDF representation)
            String mockPdfContent = "%PDF-1.4\n%Mock PDF exported from OneDrive\nThis is a mock PDF file";
            InputStream mockStream = new ByteArrayInputStream(mockPdfContent.getBytes());
            
            when(contentBuilder.get(Mockito.any())).thenReturn(mockStream);

            // Chain the mocks: drives → byDriveId → items → byDriveItemId → content
            when(driveItemItemBuilder.content()).thenReturn(contentBuilder);
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
    void testExportExecutesSuccessfully() throws Exception {
        Export task = Export.builder()
            .id("test-export")
            .type(Export.class.getName())
            .driveId(Property.ofValue("test-drive"))
            .itemId(Property.ofValue("item-to-export"))
            .format(Property.ofValue(Export.ExportFormat.PDF))
            .tenantId(Property.ofValue("mock-tenant"))
            .clientId(Property.ofValue("mock-client"))
            .clientSecret(Property.ofValue("mock-secret"))
            .build();
        
        RunContext runContext = runContextFactory.of();
        Export.Output output = task.run(runContext);
        
        // Verify export executed successfully
        assertThat(output, notNullValue());
        assertThat(output.getUri(), notNullValue());
        
        // Verify the exported content can be read
        InputStream exportedContent = storageInterface.get(
            TenantService.MAIN_TENANT,
            null,
            output.getUri()
        );
        assertThat(exportedContent, notNullValue());
        byte[] content = exportedContent.readAllBytes();
        assertThat(content.length, greaterThan(0));
    }

    @Test
    void testExportTaskConfiguration() {
        // Lightweight configuration test
        Export task = Export.builder()
            .id("test-export")
            .type(Export.class.getName())
            .driveId(Property.ofValue("test-drive"))
            .itemId(Property.ofValue("item-to-export"))
            .format(Property.ofValue(Export.ExportFormat.PDF))
            .tenantId(Property.ofValue("mock-tenant"))
            .clientId(Property.ofValue("mock-client"))
            .clientSecret(Property.ofValue("mock-secret"))
            .build();
        
        assertThat(task, notNullValue());
        assertThat(task.getDriveId(), notNullValue());
        assertThat(task.getItemId(), notNullValue());
        assertThat(task.getFormat(), notNullValue());
    }

    // ================== E2E Tests (requires credentials) ==================

    @Test
    @EnabledIf("isIntegrationTestEnabled")
    void run() throws Exception {
        // First, upload a file to export
        String fileName = FriendlyId.createFriendlyId() + ".yml";
        Upload.Output uploadOutput = testUtils.upload("Documents/TestExport", fileName);

        Export task = Export.builder()
            .id(ExportTest.class.getSimpleName())
            .type(Export.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .itemId(Property.ofValue(uploadOutput.getFile().getId()))
            .format(Property.ofValue(Export.ExportFormat.PDF))
            .build();

        Export.Output runOutput = task.run(runContext(task));

        // Verify content was exported
        assertThat(runOutput.getUri(), notNullValue());
        InputStream exportedContent = storageInterface.get(
            MAIN_TENANT,
            null,
            runOutput.getUri()
        );
        assertThat(exportedContent, notNullValue());
        
        // Verify we can read the exported content
        byte[] content = exportedContent.readAllBytes();
        assertThat(content.length > 0, notNullValue());
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
