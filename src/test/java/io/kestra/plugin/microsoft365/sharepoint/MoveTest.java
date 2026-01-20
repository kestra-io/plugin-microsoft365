package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.item.items.ItemsRequestBuilder;
import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.ItemReference;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@MicronautTest
class MoveTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldMoveItem() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();

        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);

        Move moveTask = Move.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .itemId(Property.ofValue("item-id"))
            .destinationParentId(Property.ofValue("dest-parent-id"))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDrive = mock(DriveItemRequestBuilder.class);
        ItemsRequestBuilder mockItems = mock(ItemsRequestBuilder.class);
        DriveItemItemRequestBuilder mockItem = mock(DriveItemItemRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDrive);
        when(mockDrive.items()).thenReturn(mockItems);
        when(mockItems.byDriveItemId(anyString())).thenReturn(mockItem);

        // Mock the response (moved item)
        DriveItem moved = new DriveItem();
        moved.setId("item-id");
        moved.setName("original-name.txt");
        moved.setWebUrl("https://contoso.sharepoint.com/original-name.txt");
        ItemReference movedParent = new ItemReference();
        movedParent.setId("dest-parent-id");
        moved.setParentReference(movedParent);

        when(mockItem.patch(any(DriveItem.class))).thenReturn(moved);

        // Create a spy of the task to override connection method
        Move testTask = spy(moveTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Move.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("item-id"));
        assertThat(output.getItemName(), is("original-name.txt"));
        assertThat(output.getParentId(), is("dest-parent-id"));
        assertThat(output.getWebUrl(), is("https://contoso.sharepoint.com/original-name.txt"));

        // Verify patch payload includes destination parent reference, and no rename by default
        ArgumentCaptor<DriveItem> captor = ArgumentCaptor.forClass(DriveItem.class);
        verify(mockItem).patch(captor.capture());

        DriveItem payload = captor.getValue();
        assertThat(payload.getParentReference(), notNullValue());
        assertThat(payload.getParentReference().getId(), is("dest-parent-id"));
        assertThat(payload.getName(), nullValue());
    }

    @Test
    void shouldMoveAndRenameItem() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();

        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);

        Move moveTask = Move.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .itemId(Property.ofValue("item-id"))
            .destinationParentId(Property.ofValue("dest-parent-id"))
            .newName(Property.ofValue("renamed.txt"))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDrive = mock(DriveItemRequestBuilder.class);
        ItemsRequestBuilder mockItems = mock(ItemsRequestBuilder.class);
        DriveItemItemRequestBuilder mockItem = mock(DriveItemItemRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDrive);
        when(mockDrive.items()).thenReturn(mockItems);
        when(mockItems.byDriveItemId(anyString())).thenReturn(mockItem);

        // Mock the response (moved + renamed item)
        DriveItem moved = new DriveItem();
        moved.setId("item-id");
        moved.setName("renamed.txt");
        moved.setWebUrl("https://contoso.sharepoint.com/renamed.txt");
        ItemReference movedParent = new ItemReference();
        movedParent.setId("dest-parent-id");
        moved.setParentReference(movedParent);

        when(mockItem.patch(any(DriveItem.class))).thenReturn(moved);

        // Create a spy of the task to override connection method
        Move testTask = spy(moveTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Move.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("item-id"));
        assertThat(output.getItemName(), is("renamed.txt"));
        assertThat(output.getParentId(), is("dest-parent-id"));
        assertThat(output.getWebUrl(), is("https://contoso.sharepoint.com/renamed.txt"));

        // Verify patch payload includes destination parent reference AND rename
        ArgumentCaptor<DriveItem> captor = ArgumentCaptor.forClass(DriveItem.class);
        verify(mockItem).patch(captor.capture());

        DriveItem payload = captor.getValue();
        assertThat(payload.getParentReference(), notNullValue());
        assertThat(payload.getParentReference().getId(), is("dest-parent-id"));
        assertThat(payload.getName(), is("renamed.txt"));
    }
}
