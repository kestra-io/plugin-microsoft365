package io.kestra.plugin.microsoft365.oneshare;

import com.devskiller.friendly_id.FriendlyId;
import com.google.common.collect.ImmutableMap;
import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.item.items.ItemsRequestBuilder;
import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.drives.item.items.item.children.ChildrenRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.Folder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.microsoft365.oneshare.models.ItemType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import static io.kestra.core.tenant.TenantService.MAIN_TENANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@KestraTest
class CreateTest extends AbstractOneShareTest {

    private static MockedConstruction<GraphServiceClient> graphClientMock;
    
    @BeforeAll
    static void setupMocks() {
        // Mock GraphServiceClient and the drive API chain for creating items
        graphClientMock = Mockito.mockConstruction(GraphServiceClient.class, (mock, context) -> {
            DrivesRequestBuilder drivesBuilder = mock(DrivesRequestBuilder.class);
            DriveItemRequestBuilder driveItemBuilder = mock(DriveItemRequestBuilder.class);
            ItemsRequestBuilder itemsBuilder = mock(ItemsRequestBuilder.class);
            DriveItemItemRequestBuilder driveItemItemBuilder = mock(DriveItemItemRequestBuilder.class);
            ChildrenRequestBuilder childrenBuilder = mock(ChildrenRequestBuilder.class);
            com.microsoft.graph.drives.item.items.item.content.ContentRequestBuilder contentBuilder = 
                mock(com.microsoft.graph.drives.item.items.item.content.ContentRequestBuilder.class);

            // Mock created folder response
            DriveItem mockFolder = new DriveItem();
            mockFolder.setId("created-folder-id-123");
            mockFolder.setName("test-folder");
            Folder folderFacet = new Folder();
            mockFolder.setFolder(folderFacet);
            
            // Mock created file response
            DriveItem mockFile = new DriveItem();
            mockFile.setId("created-file-id-456");
            mockFile.setName("test-file.txt");
            com.microsoft.graph.models.File fileFacet = new com.microsoft.graph.models.File();
            mockFile.setFile(fileFacet);
            
            when(childrenBuilder.post(any())).thenAnswer(invocation -> {
                DriveItem requestBody = invocation.getArgument(0);
                // Return folder or file based on the request
                if (requestBody.getFolder() != null) {
                    return mockFolder;
                } else {
                    return mockFile;
                }
            });
            
            // Mock content PUT for files with content
            when(contentBuilder.put(any())).thenReturn(mockFile);

            // Chain the mocks: drives → byDriveId → items → byDriveItemId → children/content
            when(driveItemItemBuilder.children()).thenReturn(childrenBuilder);
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
    void testCreateFolderExecutesSuccessfully() throws Exception {
        Create task = Create.builder()
            .id("test-create-folder")
            .type(Create.class.getName())
            .driveId(Property.ofValue("test-drive"))
            .parentId(Property.ofValue("root"))
            .name(Property.ofValue("test-folder"))
            .itemType(Property.ofValue(ItemType.FOLDER))
            .tenantId(Property.ofValue("mock-tenant"))
            .clientId(Property.ofValue("mock-client"))
            .clientSecret(Property.ofValue("mock-secret"))
            .build();
        
        RunContext runContext = runContextFactory.of();
        Create.Output output = task.run(runContext);
        
        // Verify folder was created
        assertThat(output, notNullValue());
        assertThat(output.getFile(), notNullValue());
        assertThat(output.getFile().getId(), is("created-folder-id-123"));
        assertThat(output.getFile().getName(), is("test-folder"));
        assertThat(output.getFile().isFolder(), is(true));
    }

    @Test
    void testCreateFileExecutesSuccessfully() throws Exception {
        Create task = Create.builder()
            .id("test-create-file")
            .type(Create.class.getName())
            .driveId(Property.ofValue("test-drive"))
            .parentId(Property.ofValue("root"))
            .name(Property.ofValue("test-file.txt"))
            .content(Property.ofValue("Test content"))
            .tenantId(Property.ofValue("mock-tenant"))
            .clientId(Property.ofValue("mock-client"))
            .clientSecret(Property.ofValue("mock-secret"))
            .build();
        
        RunContext runContext = runContextFactory.of();
        Create.Output output = task.run(runContext);
        
        // Verify file was created
        assertThat(output, notNullValue());
        assertThat(output.getFile(), notNullValue());
        assertThat(output.getFile().getId(), is("created-file-id-456"));
        assertThat(output.getFile().getName(), is("test-file.txt"));
        assertThat(output.getFile().isFolder(), is(false));
    }

    @Test
    void testCreateTaskConfiguration() {
        // Lightweight configuration test
        Create task = Create.builder()
            .id("test-create")
            .type(Create.class.getName())
            .driveId(Property.ofValue("test-drive"))
            .name(Property.ofValue("test-item"))
            .tenantId(Property.ofValue("mock-tenant"))
            .clientId(Property.ofValue("mock-client"))
            .clientSecret(Property.ofValue("mock-secret"))
            .build();
        
        assertThat(task, notNullValue());
        assertThat(task.getDriveId(), notNullValue());
        assertThat(task.getName(), notNullValue());
    }

    // ================== E2E Tests (requires credentials) ==================

    @Test
    @EnabledIf("isIntegrationTestEnabled")
    void createFolder() throws Exception {
        String folderName = "test-folder-" + FriendlyId.createFriendlyId();

        Create task = Create.builder()
            .id(CreateTest.class.getSimpleName())
            .type(Create.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root:/Documents/TestCreate"))
            .name(Property.ofValue(folderName))
            .itemType(Property.ofValue(ItemType.FOLDER))
            .build();

        Create.Output runOutput = task.run(runContext(task));

        assertThat(runOutput.getFile().isFolder(), is(true));
        assertThat(runOutput.getFile().getName(), is(folderName));
        assertThat(runOutput.getFile().getId(), notNullValue());
    }

    @Test
    @EnabledIf("isIntegrationTestEnabled")
    void createFile() throws Exception {
        String fileName = "test-file-" + FriendlyId.createFriendlyId() + ".txt";
        String content = "Hello World from Kestra!";

        Create task = Create.builder()
            .id(CreateTest.class.getSimpleName())
            .type(Create.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root:/Documents/TestCreate"))
            .name(Property.ofValue(fileName))
            .content(Property.ofValue(content))
            .build();

        Create.Output runOutput = task.run(runContext(task));

        assertThat(runOutput.getFile().isFolder(), is(false));
        assertThat(runOutput.getFile().getName(), is(fileName));
        assertThat(runOutput.getFile().getId(), notNullValue());

        // Verify file can be downloaded with the correct content
        Download downloadTask = Download.builder()
            .id(DownloadTest.class.getSimpleName())
            .type(Download.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .itemId(Property.ofValue(runOutput.getFile().getId()))
            .build();

        Download.Output downloadOutput = downloadTask.run(runContext(downloadTask));
        String downloadedContent = new String(storageInterface.get(
            MAIN_TENANT,
            null,
            downloadOutput.getUri()
        ).readAllBytes());

        assertThat(downloadedContent, is(content));
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
