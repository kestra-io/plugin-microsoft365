package io.kestra.plugin.microsoft365.oneshare;

import com.devskiller.friendly_id.FriendlyId;
import com.google.common.collect.ImmutableMap;
import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.item.items.ItemsRequestBuilder;
import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.drives.item.items.item.children.ChildrenRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.TestsUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@KestraTest
class ListFilesTest extends AbstractOneShareTest {
    @Inject
    private OnesShareTestUtils testUtils;

    private static MockedConstruction<GraphServiceClient> graphClientMock;
    
    @BeforeAll
    static void setupMocks() {
        // Mock GraphServiceClient and the drive API chain for listing files
        graphClientMock = Mockito.mockConstruction(GraphServiceClient.class, (mock, context) -> {
            DrivesRequestBuilder drivesBuilder = mock(DrivesRequestBuilder.class);
            DriveItemRequestBuilder driveItemBuilder = mock(DriveItemRequestBuilder.class);
            ItemsRequestBuilder itemsBuilder = mock(ItemsRequestBuilder.class);
            DriveItemItemRequestBuilder driveItemItemBuilder = mock(DriveItemItemRequestBuilder.class);
            ChildrenRequestBuilder childrenBuilder = mock(ChildrenRequestBuilder.class);

            // Mock the collection response with sample files
            DriveItemCollectionResponse mockResponse = new DriveItemCollectionResponse();
            
            DriveItem file1 = new DriveItem();
            file1.setId("file-1");
            file1.setName("document1.txt");
            file1.setSize(1024L);
            
            DriveItem file2 = new DriveItem();
            file2.setId("file-2");
            file2.setName("document2.pdf");
            file2.setSize(2048L);
            
            DriveItem file3 = new DriveItem();
            file3.setId("file-3");
            file3.setName("spreadsheet.xlsx");
            file3.setSize(4096L);
            
            mockResponse.setValue(Arrays.asList(file1, file2, file3));
            mockResponse.setOdataNextLink(null); // No pagination
            
            when(childrenBuilder.get()).thenReturn(mockResponse);

            // Chain the mocks: drives → byDriveId → items → byDriveItemId → children
            when(driveItemItemBuilder.children()).thenReturn(childrenBuilder);
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
    void testListFilesExecutesSuccessfully() throws Exception {
        ListFiles task = ListFiles.builder()
            .id("test-list")
            .type(ListFiles.class.getName())
            .driveId(Property.ofValue("test-drive"))
            .itemId(Property.ofValue("root"))
            .tenantId(Property.ofValue("mock-tenant"))
            .clientId(Property.ofValue("mock-client"))
            .clientSecret(Property.ofValue("mock-secret"))
            .build();
        
        RunContext runContext = runContextFactory.of();
        ListFiles.Output output = task.run(runContext);
        
        // Verify output
        assertThat(output, notNullValue());
        assertThat(output.getFiles(), notNullValue());
        assertThat(output.getFiles(), hasSize(3));
        assertThat(output.getFiles().get(0).getName(), is("document1.txt"));
        assertThat(output.getFiles().get(1).getName(), is("document2.pdf"));
        assertThat(output.getFiles().get(2).getName(), is("spreadsheet.xlsx"));
    }

    @Test
    void testListFilesTaskConfiguration() {
        // Lightweight configuration test
        ListFiles task = ListFiles.builder()
            .id("test-list")
            .type(ListFiles.class.getName())
            .driveId(Property.ofValue("test-drive"))
            .itemId(Property.ofValue("root"))
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
    @EnabledIf("isIntegrationTestEnabled")
    void run() throws Exception {
        String dir = FriendlyId.createFriendlyId();

        // Create parent folder
        Create createFolder = Create.builder()
            .id(CreateTest.class.getSimpleName())
            .type(Create.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root:/Documents/TestListFiles"))
            .name(Property.ofValue(dir))
            .folder(Property.ofValue(true))
            .build();
        
        Create.Output folderOutput = createFolder.run(TestsUtils.mockRunContext(
            this.runContextFactory,
            createFolder,
            ImmutableMap.of(
                "tenantId", tenantId,
                "clientId", clientId,
                "clientSecret", clientSecret,
                "driveId", driveId
            )
        ));

        // Upload 5 files to test directory
        for (int i = 0; i < 5; i++) {
            String fileName = FriendlyId.createFriendlyId() + ".yml";
            testUtils.upload("Documents/TestListFiles/" + dir, fileName);
        }

        // List files in the folder
        ListFiles task = task()
            .itemId(Property.ofValue(folderOutput.getFile().getId()))
            .build();
        
        ListFiles.Output run = task.run(TestsUtils.mockRunContext(
            this.runContextFactory,
            task,
            ImmutableMap.of(
                "tenantId", tenantId,
                "clientId", clientId,
                "clientSecret", clientSecret,
                "driveId", driveId
            )
        ));
        
        assertThat(run.getFiles().size(), greaterThanOrEqualTo(5));

        // Test listing root folder
        ListFiles rootTask = task()
            .itemId(Property.ofValue("root"))
            .build();
        
        ListFiles.Output rootRun = rootTask.run(TestsUtils.mockRunContext(
            this.runContextFactory,
            rootTask,
            ImmutableMap.of(
                "tenantId", tenantId,
                "clientId", clientId,
                "clientSecret", clientSecret,
                "driveId", driveId
            )
        ));
        
        assertThat(rootRun.getFiles().size(), greaterThanOrEqualTo(1));
    }

    private static ListFiles.ListFilesBuilder<?, ?> task() {
        return ListFiles.builder()
            .id(ListFilesTest.class.getSimpleName())
            .type(ListFiles.class.getName())
            .tenantId(Property.ofValue("{{ inputs.tenantId }}"))
            .clientId(Property.ofValue("{{ inputs.clientId }}"))
            .clientSecret(Property.ofValue("{{ inputs.clientSecret }}"))
            .driveId(Property.ofValue("{{ inputs.driveId }}"));
    }
}