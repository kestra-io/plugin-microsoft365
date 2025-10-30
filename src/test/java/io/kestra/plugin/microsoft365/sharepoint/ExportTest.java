package io.kestra.plugin.microsoft365.sharepoint;

import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.drives.item.items.item.content.ContentRequestBuilder;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

@KestraTest
class ExportTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldExportWordDocumentToPdf() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);

        Export exportTask = Export.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .itemId(Property.ofValue("word-doc-id"))
            .format(Property.ofValue(Export.FormatType.valueOf("pdf")))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        ContentRequestBuilder mockContent = mock(ContentRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.content()).thenReturn(mockContent);

        // Mock get() for file metadata
        DriveItem mockDriveItemResponse = new DriveItem();
        mockDriveItemResponse.setId("word-doc-id");
        mockDriveItemResponse.setName("document.docx");
        mockDriveItemResponse.setSize(10240L);
        mockDriveItemResponse.setWebUrl("https://contoso.sharepoint.com/document.docx");

        when(mockDriveItem.get()).thenReturn(mockDriveItemResponse);

        // Mock content download with format conversion
        String pdfContent = "Mock PDF content";
        InputStream mockInputStream = new ByteArrayInputStream(pdfContent.getBytes());
        when(mockContent.get(any())).thenReturn(mockInputStream);

        // Create a spy of the task to override connection method
        Export testTask = spy(exportTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Export.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("word-doc-id"));
        assertThat(output.getOriginalName(), is("document.docx"));
        assertThat(output.getName(), is("document.pdf"));
        assertThat(output.getUri(), notNullValue());
        assertThat(output.getFormat(), is("pdf"));
        assertThat(output.getWebUrl(), is("https://contoso.sharepoint.com/document.docx"));

        // Verify the format parameter was used
        verify(mockContent).get(any());
    }

    @Test
    void shouldExportExcelToPdf() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);

        Export exportTask = Export.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .itemPath(Property.ofValue("/Documents/spreadsheet.xlsx"))
            .format(Property.ofValue(Export.FormatType.valueOf("pdf")))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        ContentRequestBuilder mockContent = mock(ContentRequestBuilder.class);
        com.microsoft.graph.drives.item.items.ItemsRequestBuilder mockItems = mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class);

        // Mock get() for file metadata
        DriveItem mockDriveItemResponse = new DriveItem();
        mockDriveItemResponse.setId("excel-id");
        mockDriveItemResponse.setName("spreadsheet.xlsx");
        mockDriveItemResponse.setSize(20480L);
        mockDriveItemResponse.setWebUrl("https://contoso.sharepoint.com/Documents/spreadsheet.xlsx");

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mockItems);
        when(mockItems.byDriveItemId(contains("root:"))).thenReturn(mockDriveItem);
        when(mockItems.byDriveItemId("excel-id")).thenReturn(mockDriveItem);
        when(mockDriveItem.get()).thenReturn(mockDriveItemResponse);
        when(mockDriveItem.content()).thenReturn(mockContent);

        // Mock content download
        String pdfContent = "Mock Excel PDF content";
        InputStream mockInputStream = new ByteArrayInputStream(pdfContent.getBytes());
        when(mockContent.get(any())).thenReturn(mockInputStream);

        // Create a spy of the task to override connection method
        Export testTask = spy(exportTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Export.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("excel-id"));
        assertThat(output.getOriginalName(), is("spreadsheet.xlsx"));
        assertThat(output.getName(), is("spreadsheet.pdf"));
        assertThat(output.getUri(), notNullValue());
        assertThat(output.getFormat(), is("pdf"));
        assertThat(output.getOriginalSize(), is(20480L));
    }

    @Test
    void shouldExportPowerPointToPdf() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);

        Export exportTask = Export.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .itemId(Property.ofValue("ppt-id"))
            .format(Property.ofValue(Export.FormatType.valueOf("pdf")))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        ContentRequestBuilder mockContent = mock(ContentRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.content()).thenReturn(mockContent);

        // Mock get() for file metadata
        DriveItem mockDriveItemResponse = new DriveItem();
        mockDriveItemResponse.setId("ppt-id");
        mockDriveItemResponse.setName("presentation.pptx");
        mockDriveItemResponse.setSize(51200L);
        mockDriveItemResponse.setWebUrl("https://contoso.sharepoint.com/presentation.pptx");

        when(mockDriveItem.get()).thenReturn(mockDriveItemResponse);

        // Mock content download
        byte[] pdfContent = new byte[1024];
        InputStream mockInputStream = new ByteArrayInputStream(pdfContent);
        when(mockContent.get(any())).thenReturn(mockInputStream);

        // Create a spy of the task to override connection method
        Export testTask = spy(exportTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Export.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("ppt-id"));
        assertThat(output.getOriginalName(), is("presentation.pptx"));
        assertThat(output.getName(), is("presentation.pdf"));
        assertThat(output.getUri(), notNullValue());
        assertThat(output.getFormat(), is("pdf"));
    }

    @Test
    void shouldExportDocumentToHtml() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);

        Export exportTask = Export.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .itemId(Property.ofValue("doc-id"))
            .format(Property.ofValue(Export.FormatType.valueOf("html")))
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        ContentRequestBuilder mockContent = mock(ContentRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(com.microsoft.graph.drives.item.items.ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.content()).thenReturn(mockContent);

        // Mock get() for file metadata
        DriveItem mockDriveItemResponse = new DriveItem();
        mockDriveItemResponse.setId("doc-id");
        mockDriveItemResponse.setName("article.md");
        mockDriveItemResponse.setSize(2048L);
        mockDriveItemResponse.setWebUrl("https://contoso.sharepoint.com/article.md");

        when(mockDriveItem.get()).thenReturn(mockDriveItemResponse);

        // Mock content download
        String htmlContent = "<html><body>Mock HTML content</body></html>";
        InputStream mockInputStream = new ByteArrayInputStream(htmlContent.getBytes());
        when(mockContent.get(any())).thenReturn(mockInputStream);

        // Create a spy of the task to override connection method
        Export testTask = spy(exportTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Export.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("doc-id"));
        assertThat(output.getOriginalName(), is("article.md"));
        assertThat(output.getName(), is("article.html"));
        assertThat(output.getUri(), notNullValue());
        assertThat(output.getFormat(), is("html"));
    }
}
