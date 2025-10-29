package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

@KestraTest
class UploadTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private StorageInterface storageInterface;

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
    void shouldUploadFile() throws Exception {
        // Given
        String fileContent = "Test file content";
        URI fileUri = runContext.storage().putFile(new ByteArrayInputStream(fileContent.getBytes()), "test.txt");

        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue("uploaded-file.txt"))
            .parentId(Property.ofValue("parent-folder-id"))
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
        mockDriveItemResponse.setId("uploaded-file-id");
        mockDriveItemResponse.setName("uploaded-file.txt");
        mockDriveItemResponse.setWebUrl("https://contoso.sharepoint.com/uploaded-file.txt");
        mockDriveItemResponse.setSize(17L);

        when(mockContent.put(any(InputStream.class))).thenReturn(mockDriveItemResponse);

        // Create a spy of the task to override connection method
        Upload testTask = spy(uploadTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Upload.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("uploaded-file-id"));
        assertThat(output.getName(), is("uploaded-file.txt"));
        assertThat(output.getWebUrl(), is("https://contoso.sharepoint.com/uploaded-file.txt"));
        assertThat(output.getSize(), is(17L));

        // Verify upload was called
        verify(mockContent).put(any(InputStream.class));
    }

    @Test
    void shouldUploadFileToRoot() throws Exception {
        // Given
        String fileContent = "Root file content";
        URI fileUri = runContext.storage().putFile(new ByteArrayInputStream(fileContent.getBytes()), "root-file.txt");

        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue("root-file.txt"))
            .parentId(Property.ofValue("root"))
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
        when(mockDriveItems.items().byDriveItemId(contains("root"))).thenReturn(mockDriveItem);
        when(mockDriveItem.content()).thenReturn(mockContent);

        // Mock the response
        DriveItem mockDriveItemResponse = new DriveItem();
        mockDriveItemResponse.setId("root-file-id");
        mockDriveItemResponse.setName("root-file.txt");
        mockDriveItemResponse.setWebUrl("https://contoso.sharepoint.com/root-file.txt");
        mockDriveItemResponse.setSize(17L);

        when(mockContent.put(any(InputStream.class))).thenReturn(mockDriveItemResponse);

        // Create a spy of the task to override connection method
        Upload testTask = spy(uploadTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Upload.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("root-file-id"));
        assertThat(output.getName(), is("root-file.txt"));
        assertThat(output.getWebUrl(), is("https://contoso.sharepoint.com/root-file.txt"));
    }

    @Test
    void shouldUploadLargeFile() throws Exception {
        // Given - simulate a large file
        byte[] largeContent = new byte[5 * 1024 * 1024]; // 5MB
        URI fileUri = runContext.storage().putFile(new ByteArrayInputStream(largeContent), "large-file.pdf");

        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue("large-file.pdf"))
            .parentId(Property.ofValue("documents"))
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
        mockDriveItemResponse.setId("large-file-id");
        mockDriveItemResponse.setName("large-file.pdf");
        mockDriveItemResponse.setWebUrl("https://contoso.sharepoint.com/large-file.pdf");
        mockDriveItemResponse.setSize((long) largeContent.length);

        when(mockContent.put(any(InputStream.class))).thenReturn(mockDriveItemResponse);

        // Create a spy of the task to override connection method
        Upload testTask = spy(uploadTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Upload.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("large-file-id"));
        assertThat(output.getName(), is("large-file.pdf"));
        assertThat(output.getSize(), is((long) largeContent.length));
    }
}
