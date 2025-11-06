package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

@KestraTest
class DeleteTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldDeleteItem() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);
        
        Delete deleteTask = Delete.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .itemId(Property.ofValue("item-to-delete"))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);

        // Mock delete() to do nothing (successful deletion)
        doNothing().when(mockDriveItem).delete();

        // Create a spy of the task to override connection method
        Delete testTask = spy(deleteTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Delete.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("item-to-delete"));

        // Verify delete was called
        verify(mockDriveItem).delete();
    }

    @Test
    void shouldDeleteItemFromRoot() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);
        
        Delete deleteTask = Delete.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .itemId(Property.ofValue("root-item-id"))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId("root-item-id")).thenReturn(mockDriveItem);

        doNothing().when(mockDriveItem).delete();

        // Create a spy of the task to override connection method
        Delete testTask = spy(deleteTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Delete.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("root-item-id"));
        verify(mockDriveItem).delete();
    }
}
