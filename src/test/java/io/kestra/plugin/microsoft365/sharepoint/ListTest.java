package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.drives.item.items.item.children.ChildrenRequestBuilder;
import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemCollectionResponse;
import com.microsoft.graph.models.File;
import com.microsoft.graph.models.Folder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

@MicronautTest
class ListTest {
    @Inject
    private RunContextFactory runContextFactory;

    private RunContext runContext;
    private SharepointConnection mockConnection;
    private GraphServiceClient mockClient;

    @BeforeEach
    void setUp() {
        runContext = runContextFactory.of();
        mockConnection = mock(SharepointConnection.class);
        mockClient = mock(GraphServiceClient.class);
    }

    @Test
    void shouldListItemsInFolder() throws Exception {
        // Given
        List listTask = List.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .folderId(Property.ofValue("folder-id"))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        ChildrenRequestBuilder mockChildren = mock(ChildrenRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.children()).thenReturn(mockChildren);

        // Create mock drive items
        DriveItem file1 = new DriveItem();
        file1.setId("file-1");
        file1.setName("document.txt");
        file1.setSize(1024L);
        file1.setWebUrl("https://contoso.sharepoint.com/document.txt");
        file1.setCreatedDateTime(OffsetDateTime.now());
        file1.setLastModifiedDateTime(OffsetDateTime.now());
        file1.setFile(new File());

        DriveItem folder1 = new DriveItem();
        folder1.setId("folder-1");
        folder1.setName("Subfolder");
        folder1.setSize(0L);
        folder1.setWebUrl("https://contoso.sharepoint.com/Subfolder");
        folder1.setCreatedDateTime(OffsetDateTime.now());
        folder1.setLastModifiedDateTime(OffsetDateTime.now());
        folder1.setFolder(new Folder());

        DriveItemCollectionResponse mockResponse = new DriveItemCollectionResponse();
        mockResponse.setValue(Arrays.asList(file1, folder1));

        when(mockChildren.get()).thenReturn(mockResponse);

        // Create a spy of the task to override connection method
        List testTask = spy(listTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        List.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItems(), hasSize(2));
        assertThat(output.getSize(), is(2));

        // Verify first item (file)
        assertThat(output.getItems().getFirst().getId(), is("file-1"));
        assertThat(output.getItems().getFirst().getName(), is("document.txt"));
        assertThat(output.getItems().get(0).getIsFile(), is(true));
        assertThat(output.getItems().get(0).getIsFolder(), is(false));

        // Verify second item (folder)
        assertThat(output.getItems().get(1).getId(), is("folder-1"));
        assertThat(output.getItems().get(1).getName(), is("Subfolder"));
        assertThat(output.getItems().get(1).getIsFolder(), is(true));
        assertThat(output.getItems().get(1).getIsFile(), is(false));
    }

    @Test
    void shouldListItemsInRoot() throws Exception {
        // Given
        List listTask = List.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .folderId(Property.ofValue("root"))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        ChildrenRequestBuilder mockChildren = mock(ChildrenRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId("root")).thenReturn(mockDriveItem);
        when(mockDriveItem.children()).thenReturn(mockChildren);

        // Create mock drive items
        DriveItem file1 = new DriveItem();
        file1.setId("root-file-1");
        file1.setName("readme.md");
        file1.setSize(512L);
        file1.setWebUrl("https://contoso.sharepoint.com/readme.md");
        file1.setCreatedDateTime(OffsetDateTime.now());
        file1.setLastModifiedDateTime(OffsetDateTime.now());
        file1.setFile(new File());

        DriveItemCollectionResponse mockResponse = new DriveItemCollectionResponse();
        mockResponse.setValue(Arrays.asList(file1));

        when(mockChildren.get()).thenReturn(mockResponse);

        // Create a spy of the task to override connection method
        List testTask = spy(listTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        List.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItems(), hasSize(1));
        assertThat(output.getSize(), is(1));
        assertThat(output.getItems().getFirst().getId(), is("root-file-1"));
        assertThat(output.getItems().getFirst().getName(), is("readme.md"));
    }

    @Test
    void shouldReturnEmptyListWhenFolderIsEmpty() throws Exception {
        // Given
        List listTask = List.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .folderId(Property.ofValue("empty-folder-id"))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        ChildrenRequestBuilder mockChildren = mock(ChildrenRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.children()).thenReturn(mockChildren);

        DriveItemCollectionResponse mockResponse = new DriveItemCollectionResponse();
        mockResponse.setValue(Arrays.asList());

        when(mockChildren.get()).thenReturn(mockResponse);

        // Create a spy of the task to override connection method
        List testTask = spy(listTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        List.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItems(), hasSize(0));
        assertThat(output.getSize(), is(0));
    }

    @Test
    void shouldReturnOnlyFirstItemWhenFetchTypeIsFetchOne() throws Exception {
        // Given
        List listTask = List.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .folderId(Property.ofValue("folder-id"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        ChildrenRequestBuilder mockChildren = mock(ChildrenRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.children()).thenReturn(mockChildren);

        // Create mock drive items
        DriveItem file1 = new DriveItem();
        file1.setId("file-1");
        file1.setName("first.txt");
        file1.setSize(1024L);
        file1.setWebUrl("https://contoso.sharepoint.com/first.txt");
        file1.setCreatedDateTime(OffsetDateTime.now());
        file1.setLastModifiedDateTime(OffsetDateTime.now());
        file1.setFile(new File());

        DriveItem file2 = new DriveItem();
        file2.setId("file-2");
        file2.setName("second.txt");
        file2.setSize(2048L);
        file2.setWebUrl("https://contoso.sharepoint.com/second.txt");
        file2.setCreatedDateTime(OffsetDateTime.now());
        file2.setLastModifiedDateTime(OffsetDateTime.now());
        file2.setFile(new File());

        DriveItemCollectionResponse mockResponse = new DriveItemCollectionResponse();
        mockResponse.setValue(Arrays.asList(file1, file2));

        when(mockChildren.get()).thenReturn(mockResponse);

        // Create a spy of the task to override connection method
        List testTask = spy(listTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        List.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItems(), hasSize(1));
        assertThat(output.getSize(), is(1));
        assertThat(output.getItem(), is(notNullValue()));
        assertThat(output.getItem().getId(), is("file-1"));
        assertThat(output.getItem().getName(), is("first.txt"));
        assertThat(output.getItems().getFirst().getId(), is("file-1"));
        assertThat(output.getItems().getFirst().getName(), is("first.txt"));
        assertThat(output.getUri(), is(nullValue()));
    }

    @Test
    void shouldReturnEmptyListWhenFetchTypeIsFetchOneButNoItems() throws Exception {
        // Given
        List listTask = List.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .folderId(Property.ofValue("empty-folder-id"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        ChildrenRequestBuilder mockChildren = mock(ChildrenRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.children()).thenReturn(mockChildren);

        DriveItemCollectionResponse mockResponse = new DriveItemCollectionResponse();
        mockResponse.setValue(Arrays.asList());

        when(mockChildren.get()).thenReturn(mockResponse);

        // Create a spy of the task to override connection method
        List testTask = spy(listTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        List.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItems(), hasSize(0));
        assertThat(output.getSize(), is(0));
        assertThat(output.getItem(), is(nullValue()));
        assertThat(output.getUri(), is(nullValue()));
    }

    @Test
    void shouldStoreItemsToFileWhenFetchTypeIsStore() throws Exception {
        // Given
        List listTask = List.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .folderId(Property.ofValue("folder-id"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        ChildrenRequestBuilder mockChildren = mock(ChildrenRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.children()).thenReturn(mockChildren);

        // Create mock drive items
        DriveItem file1 = new DriveItem();
        file1.setId("file-1");
        file1.setName("document.txt");
        file1.setSize(1024L);
        file1.setWebUrl("https://contoso.sharepoint.com/document.txt");
        file1.setCreatedDateTime(OffsetDateTime.now());
        file1.setLastModifiedDateTime(OffsetDateTime.now());
        file1.setFile(new File());

        DriveItem folder1 = new DriveItem();
        folder1.setId("folder-1");
        folder1.setName("Subfolder");
        folder1.setSize(0L);
        folder1.setWebUrl("https://contoso.sharepoint.com/Subfolder");
        folder1.setCreatedDateTime(OffsetDateTime.now());
        folder1.setLastModifiedDateTime(OffsetDateTime.now());
        folder1.setFolder(new Folder());

        DriveItemCollectionResponse mockResponse = new DriveItemCollectionResponse();
        mockResponse.setValue(Arrays.asList(file1, folder1));

        when(mockChildren.get()).thenReturn(mockResponse);

        // Create a spy of the task to override connection method
        List testTask = spy(listTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        List.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItems(), is(nullValue()));
        assertThat(output.getSize(), is(2));
        assertThat(output.getUri(), is(notNullValue()));

        // Verify the stored file contains the items
        var items = Flux.from(FileSerde.readAll(
            new BufferedReader(new InputStreamReader(runContext.storage().getFile(output.getUri())))
        )).collectList().block();
        
        assertThat(items, hasSize(2));
    }

    @Test
    void shouldReturnEmptyListWhenFetchTypeIsNone() throws Exception {
        // Given
        List listTask = List.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .folderId(Property.ofValue("folder-id"))
            .fetchType(Property.ofValue(FetchType.NONE))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        ChildrenRequestBuilder mockChildren = mock(ChildrenRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.children()).thenReturn(mockChildren);

        // Create mock drive items
        DriveItem file1 = new DriveItem();
        file1.setId("file-1");
        file1.setName("document.txt");
        file1.setSize(1024L);
        file1.setWebUrl("https://contoso.sharepoint.com/document.txt");
        file1.setCreatedDateTime(OffsetDateTime.now());
        file1.setLastModifiedDateTime(OffsetDateTime.now());
        file1.setFile(new File());

        DriveItemCollectionResponse mockResponse = new DriveItemCollectionResponse();
        mockResponse.setValue(Arrays.asList(file1));

        when(mockChildren.get()).thenReturn(mockResponse);

        // Create a spy of the task to override connection method
        List testTask = spy(listTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        List.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItems(), hasSize(0));
        assertThat(output.getSize(), is(0));
        assertThat(output.getUri(), is(nullValue()));
    }
}
