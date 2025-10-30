package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.drives.item.items.item.children.ChildrenRequestBuilder;
import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.Folder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

@MicronautTest
class CreateTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldCreateFolder() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);
        
        Create createTask = Create.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .parentId(Property.ofValue("parent-folder-id"))
            .name(Property.ofValue("TestFolder"))
            .itemType(Property.ofValue(Create.ItemType.FOLDER))
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

        // Mock the response
        DriveItem mockDriveItemResponse = new DriveItem();
        mockDriveItemResponse.setId("new-folder-id");
        mockDriveItemResponse.setName("TestFolder");
        mockDriveItemResponse.setWebUrl("https://contoso.sharepoint.com/TestFolder");
        mockDriveItemResponse.setFolder(new Folder());

        when(mockChildren.post(any(DriveItem.class))).thenReturn(mockDriveItemResponse);

        // Create a spy of the task to override connection method
        Create testTask = spy(createTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Create.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("new-folder-id"));
        assertThat(output.getItemName(), is("TestFolder"));
        assertThat(output.getWebUrl(), is("https://contoso.sharepoint.com/TestFolder"));

        // Verify the folder was created with correct properties
        ArgumentCaptor<DriveItem> captor = ArgumentCaptor.forClass(DriveItem.class);
        verify(mockChildren).post(captor.capture());
        assertThat(captor.getValue().getName(), is("TestFolder"));
        assertThat(captor.getValue().getFolder(), notNullValue());
    }

    @Test
    void shouldCreateFileWithContent() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);
        
        Create createTask = Create.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .parentId(Property.ofValue("parent-folder-id"))
            .name(Property.ofValue("test.txt"))
            .itemType(Property.ofValue(Create.ItemType.FILE))
            .content(Property.ofValue("Hello, SharePoint!"))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain for file upload
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        var mockContent = mock(com.microsoft.graph.drives.item.items.item.content.ContentRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.content()).thenReturn(mockContent);

        // Mock the response
        DriveItem mockDriveItemResponse = new DriveItem();
        mockDriveItemResponse.setId("new-file-id");
        mockDriveItemResponse.setName("test.txt");
        mockDriveItemResponse.setWebUrl("https://contoso.sharepoint.com/test.txt");
        mockDriveItemResponse.setFile(new com.microsoft.graph.models.File());

        when(mockContent.put(any(ByteArrayInputStream.class))).thenReturn(mockDriveItemResponse);

        // Create a spy of the task to override connection method
        Create testTask = spy(createTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Create.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("new-file-id"));
        assertThat(output.getItemName(), is("test.txt"));
        assertThat(output.getWebUrl(), is("https://contoso.sharepoint.com/test.txt"));

        // Verify content was uploaded
        verify(mockContent).put(any(ByteArrayInputStream.class));
    }

    @Test
    void shouldCreateEmptyFile() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);
        
        Create createTask = Create.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .parentId(Property.ofValue("root"))
            .name(Property.ofValue("empty.txt"))
            .itemType(Property.ofValue(Create.ItemType.FILE))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        var mockContent = mock(com.microsoft.graph.drives.item.items.item.content.ContentRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.content()).thenReturn(mockContent);

        // Mock the response
        DriveItem mockDriveItemResponse = new DriveItem();
        mockDriveItemResponse.setId("empty-file-id");
        mockDriveItemResponse.setName("empty.txt");
        mockDriveItemResponse.setWebUrl("https://contoso.sharepoint.com/empty.txt");
        mockDriveItemResponse.setFile(new com.microsoft.graph.models.File());

        when(mockContent.put(any(ByteArrayInputStream.class))).thenReturn(mockDriveItemResponse);

        // Create a spy of the task to override connection method
        Create testTask = spy(createTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Create.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("empty-file-id"));
        assertThat(output.getItemName(), is("empty.txt"));
    }
}
