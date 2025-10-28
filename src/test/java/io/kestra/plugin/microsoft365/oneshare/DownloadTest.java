package io.kestra.plugin.microsoft365.oneshare;

import com.devskiller.friendly_id.FriendlyId;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Objects;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@KestraTest
class DownloadTest extends AbstractOneShareTest {

    private static MockedConstruction<GraphServiceClient> graphClientMock;
    private static final String MOCK_FILE_CONTENT = "This is mock file content from OneDrive";

    @BeforeAll
    static void setupMocks() {
        // Mock GraphServiceClient and the drive API chain
        graphClientMock = Mockito.mockConstruction(GraphServiceClient.class, (mock, context) -> {
            DrivesRequestBuilder drivesBuilder = mock(DrivesRequestBuilder.class);
            DriveItemRequestBuilder driveItemBuilder = mock(DriveItemRequestBuilder.class);
            ItemsRequestBuilder itemsBuilder = mock(ItemsRequestBuilder.class);
            DriveItemItemRequestBuilder driveItemItemBuilder = mock(DriveItemItemRequestBuilder.class);
            ContentRequestBuilder contentBuilder = mock(ContentRequestBuilder.class);

            // Mock the file content stream
            InputStream mockStream = new ByteArrayInputStream(MOCK_FILE_CONTENT.getBytes());
            when(contentBuilder.get()).thenReturn(mockStream);

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
    void testDownloadExecutesSuccessfully() throws Exception {
        // Unit test that actually executes the download with mocked GraphClient
        Download task = Download.builder()
            .id("test-download")
            .type(Download.class.getName())
            .driveId(Property.ofValue("test-drive-id"))
            .itemId(Property.ofValue("test-item-id"))
            .tenantId(Property.ofValue("mock-tenant"))
            .clientId(Property.ofValue("mock-client"))
            .clientSecret(Property.ofValue("mock-secret"))
            .build();
        
        RunContext runContext = runContextFactory.of();
        Download.Output output = task.run(runContext);
        
        // Verify output
        assertThat(output, notNullValue());
        assertThat(output.getUri(), notNullValue());
        
        // Verify the downloaded content
        try (InputStream is = storageInterface.get(TenantService.MAIN_TENANT, null, output.getUri())) {
            String content = CharStreams.toString(new InputStreamReader(is));
            assertThat(content, is(MOCK_FILE_CONTENT));
        }
    }

    @Test
    void testDownloadWithDifferentIds() throws Exception {
        // Test download with different drive and item IDs
        Download task = Download.builder()
            .id("download-task")
            .type(Download.class.getName())
            .driveId(Property.ofValue("drive-xyz-789"))
            .itemId(Property.ofValue("item-abc-456"))
            .tenantId(Property.ofValue("tenant-id"))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .build();
        
        RunContext runContext = runContextFactory.of();
        Download.Output output = task.run(runContext);
        
        assertThat(output, notNullValue());
        assertThat(output.getUri(), notNullValue());
    }

    // ================== E2E Tests (requires credentials) ==================
    
    @Test
    @EnabledIf("isIntegrationTestEnabled")
    void fromStorage() throws Exception {
        File file = new File(Objects.requireNonNull(DownloadTest.class.getClassLoader()
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
            .parentId(Property.ofValue("root:/Documents/TestDownload"))
            .fileName(Property.ofValue(fileName))
            .from(Property.ofValue(source.toString()))
            .build();

        Upload.Output uploadOutput = upload.run(runContext(upload));

        Download task = Download.builder()
            .id(DownloadTest.class.getSimpleName())
            .type(Download.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .itemId(Property.ofValue(uploadOutput.getFile().getId()))
            .build();

        Download.Output run = task.run(runContext(task));
        assertThat(run.getUri().toString(), endsWith(".yml"));

        InputStream get = storageInterface.get(TenantService.MAIN_TENANT, null, run.getUri());

        assertThat(
            CharStreams.toString(new InputStreamReader(get)),
            is(CharStreams.toString(new InputStreamReader(new FileInputStream(file))))
        );
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
