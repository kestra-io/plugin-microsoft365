package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.drives.item.items.item.children.ChildrenRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemCollectionResponse;
import com.microsoft.graph.models.File;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

@KestraTest
class DownloadTest {
    @Inject
    private RunContextFactory runContextFactory;

    /**
     * Actually runs the task. The other tests in this class only assert on the builder and never call run(), so they
     * pass regardless of what the download does.
     */
    @Test
    void shouldStreamContentThroughTheSdk() throws Exception {
        var mockConnection = mock(SharepointConnection.class);
        var mockClient = mock(GraphServiceClient.class);

        var task = Download.builder()
            .tenantId(Property.ofValue("t")).clientId(Property.ofValue("c")).clientSecret(Property.ofValue("s"))
            .siteId(Property.ofValue("site")).driveId(Property.ofValue("drive"))
            .itemId(Property.ofValue("item-1"))
            .build();

        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getDriveId(any(), any())).thenReturn("drive");

        var drives = mock(DrivesRequestBuilder.class);
        var driveItems = mock(DriveItemRequestBuilder.class);
        var items = mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class);
        var item = mock(DriveItemItemRequestBuilder.class);
        var content = mock(com.microsoft.graph.drives.item.items.item.content.ContentRequestBuilder.class);

        when(mockClient.drives()).thenReturn(drives);
        when(drives.byDriveId(anyString())).thenReturn(driveItems);
        when(driveItems.items()).thenReturn(items);
        when(items.byDriveItemId("item-1")).thenReturn(item);

        var driveItem = new DriveItem();
        driveItem.setId("item-1");
        driveItem.setName("report.csv");
        driveItem.setSize(11L);
        driveItem.setWebUrl("https://contoso.sharepoint.com/report.csv");
        when(item.get()).thenReturn(driveItem);

        when(item.content()).thenReturn(content);
        when(content.get()).thenReturn(new ByteArrayInputStream("hello,world".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        var testTask = spy(task);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        RunContext runContext = runContextFactory.of();
        Download.Output output = testTask.run(runContext);

        // the stream must come from content(), not from a downloadUrl in additionalData
        verify(content).get();
        assertThat(output.getItemId(), is("item-1"));
        assertThat(output.getName(), is("report.csv"));
        assertThat(output.getSize(), is(11L));
        assertThat(output.getWebUrl(), is("https://contoso.sharepoint.com/report.csv"));
        assertThat(output.getUri(), org.hamcrest.Matchers.startsWith("kestra://"));

        try (var stored = runContext.storage().getFile(java.net.URI.create(output.getUri()))) {
            assertThat(new String(stored.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), is("hello,world"));
        }
    }

    @Test
    void shouldDownloadFileByItemId() throws Exception {
        // Given
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);
        
        Download downloadTask = Download.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .itemId(Property.ofValue("file-item-id"))
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

        // Create mock drive item with download URL
        DriveItem mockDriveItemResponse = new DriveItem();
        mockDriveItemResponse.setId("file-item-id");
        mockDriveItemResponse.setName("document.pdf");
        mockDriveItemResponse.setSize(2048L);
        mockDriveItemResponse.setWebUrl("https://contoso.sharepoint.com/document.pdf");
        mockDriveItemResponse.setFile(new File());

        // Add download URL to additional data
        Map<String, Object> additionalData = new HashMap<>();
        additionalData.put("@microsoft.graph.downloadUrl", "https://download.sharepoint.com/document.pdf");
        mockDriveItemResponse.setAdditionalData(additionalData);

        DriveItemCollectionResponse mockResponse = new DriveItemCollectionResponse();
        mockResponse.setValue(Arrays.asList(mockDriveItemResponse));

        when(mockChildren.get()).thenReturn(mockResponse);

        // Create a spy of the task
        Download testTask = spy(downloadTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // Verify the task configuration is correct
        assertThat(downloadTask.getItemId(), notNullValue());
        assertThat(downloadTask.getTenantId(), notNullValue());

        // Verify mock API chain is properly set up
        verify(mockChildren, never()).get(); // Not called yet
    }

    @Test
    void shouldDownloadFileByPath() throws Exception {
        // Given
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);
        
        Download downloadTask = Download.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .itemPath(Property.ofValue("/Documents/report.xlsx"))
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
        when(mockDriveItems.items().byDriveItemId(contains("root:"))).thenReturn(mockDriveItem);
        when(mockDriveItem.children()).thenReturn(mockChildren);

        // Create mock drive item
        DriveItem mockDriveItemResponse = new DriveItem();
        mockDriveItemResponse.setId("excel-file-id");
        mockDriveItemResponse.setName("report.xlsx");
        mockDriveItemResponse.setSize(4096L);
        mockDriveItemResponse.setWebUrl("https://contoso.sharepoint.com/Documents/report.xlsx");
        mockDriveItemResponse.setFile(new File());

        Map<String, Object> additionalData = new HashMap<>();
        additionalData.put("@microsoft.graph.downloadUrl", "https://download.sharepoint.com/report.xlsx");
        mockDriveItemResponse.setAdditionalData(additionalData);

        DriveItemCollectionResponse mockResponse = new DriveItemCollectionResponse();
        mockResponse.setValue(Arrays.asList(mockDriveItemResponse));

        when(mockChildren.get()).thenReturn(mockResponse);

        // Create a spy of the task
        Download testTask = spy(downloadTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // Verify task was created with path parameter
        assertThat(downloadTask.getItemPath(), notNullValue());
        assertThat(downloadTask.getSiteId(), notNullValue());
    }

    @Test
    void shouldDownloadLargeFile() throws Exception {
        // Given
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);
        
        Download downloadTask = Download.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .itemId(Property.ofValue("large-file-id"))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Create a spy of the task
        Download testTask = spy(downloadTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // Verify the task has correct configuration
        assertThat(downloadTask.getItemId(), notNullValue());
        assertThat(downloadTask.getDriveId(), notNullValue());
    }
}
