package io.kestra.plugin.microsoft365.sharepoint;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.item.items.ItemsRequestBuilder;
import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.drives.item.items.item.content.ContentRequestBuilder;
import com.microsoft.graph.drives.item.items.item.createuploadsession.CreateUploadSessionPostRequestBody;
import com.microsoft.graph.drives.item.items.item.createuploadsession.CreateUploadSessionRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.UploadSession;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.serialization.JsonParseNodeFactory;
import com.microsoft.kiota.serialization.ParseNodeFactoryRegistry;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@KestraTest
class UploadTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeAll
    static void registerJsonParseNodeFactory() {
        // Normally registered as a side effect of building a real GraphServiceClient; the chunked-upload tests below
        // bypass that construction to hit WireMock directly, so the JSON factory needs registering explicitly here.
        ParseNodeFactoryRegistry.defaultInstance.contentTypeAssociatedFactories.put("application/json", new JsonParseNodeFactory());
    }

    @Test
    void shouldUploadFile() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);

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
        var mockContent = mock(ContentRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(ItemsRequestBuilder.class));
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
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);

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
        var mockContent = mock(ContentRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(ItemsRequestBuilder.class));
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
    void shouldUseSimpleUploadAtExactSizeLimit() throws Exception {
        // Given: a file exactly at the 4MB resumable-upload threshold (<=, not <)
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);

        byte[] content = new byte[4 * 1024 * 1024]; // exactly 4,194,304 bytes, the resumable-upload threshold
        URI fileUri = runContext.storage().putFile(new ByteArrayInputStream(content), "boundary.bin");

        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue("boundary.bin"))
            .build();

        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        var mockContent = mock(ContentRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.content()).thenReturn(mockContent);

        DriveItem mockDriveItemResponse = new DriveItem();
        mockDriveItemResponse.setId("boundary-file-id");
        mockDriveItemResponse.setName("boundary.bin");
        mockDriveItemResponse.setSize((long) content.length);
        when(mockContent.put(any(InputStream.class))).thenReturn(mockDriveItemResponse);

        Upload testTask = spy(uploadTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When
        Upload.Output output = testTask.run(runContext);

        // Then: a file of exactly 4,194,304 bytes must still use simple upload, not a resumable session
        assertThat(output.getItemId(), is("boundary-file-id"));
        assertThat(output.getSize(), is((long) content.length));
        verify(mockContent).put(any(InputStream.class));
        verify(mockDriveItem, never()).createUploadSession();
    }

    @Test
    void shouldUseResumableUploadForLargeFile() throws Exception {
        // Given: a file above the 4MB resumable-upload threshold
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);

        byte[] largeContent = new byte[5 * 1024 * 1024]; // 5MB, above the 4MB resumable-upload threshold
        URI fileUri = runContext.storage().putFile(new ByteArrayInputStream(largeContent), "large-file.pdf");

        long chunkSize = 327_680L * 2; // 640 KiB, a valid multiple of 320 KiB
        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue("large-file.pdf"))
            .parentId(Property.ofValue("documents"))
            .chunkSize(Property.ofValue(chunkSize))
            .conflictBehavior(Upload.ConflictBehavior.RENAME)
            .build();

        // Mock the SharePoint connection
        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getSiteId(any())).thenReturn("test-site-id");
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        // Mock the Graph API chain
        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        var mockContent = mock(ContentRequestBuilder.class);
        var mockCreateUploadSession = mock(CreateUploadSessionRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.content()).thenReturn(mockContent);
        when(mockDriveItem.createUploadSession()).thenReturn(mockCreateUploadSession);

        var uploadSession = new UploadSession();
        uploadSession.setUploadUrl("https://contoso.sharepoint.com/_api/upload-session/abc");

        ArgumentCaptor<CreateUploadSessionPostRequestBody> requestBodyCaptor = ArgumentCaptor.forClass(CreateUploadSessionPostRequestBody.class);
        when(mockCreateUploadSession.post(requestBodyCaptor.capture())).thenReturn(uploadSession);

        // Mock the response
        DriveItem mockDriveItemResponse = new DriveItem();
        mockDriveItemResponse.setId("large-file-id");
        mockDriveItemResponse.setName("large-file.pdf");
        mockDriveItemResponse.setWebUrl("https://contoso.sharepoint.com/large-file.pdf");
        mockDriveItemResponse.setSize((long) largeContent.length);

        // Create a spy of the task to override connection, and the actual chunk transfer
        // (LargeFileUploadTask performs real HTTP calls against the upload session URL, which is out of scope for this test)
        Upload testTask = spy(uploadTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));
        doReturn(mockDriveItemResponse).when(testTask)
            .uploadInChunks(eq(runContext), eq(uploadSession), any(InputStream.class), eq((long) largeContent.length), eq(chunkSize));

        // When
        Upload.Output output = testTask.run(runContext);

        // Then
        assertThat(output.getItemId(), is("large-file-id"));
        assertThat(output.getName(), is("large-file.pdf"));
        assertThat(output.getSize(), is((long) largeContent.length));

        // A file above the 4MB threshold must go through a resumable upload session, not a direct PUT
        verify(mockCreateUploadSession).post(any());
        verify(mockContent, never()).put(any(InputStream.class));
        verify(testTask).uploadInChunks(eq(runContext), eq(uploadSession), any(InputStream.class), eq((long) largeContent.length), eq(chunkSize));

        // conflictBehavior must be forwarded to the upload session request body
        assertThat(
            requestBodyCaptor.getValue().getItem().getAdditionalData().get("@microsoft.graph.conflictBehavior"),
            is("rename")
        );
    }

    @Test
    void shouldFailWhenUploadSessionIsNull() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);

        byte[] largeContent = new byte[5 * 1024 * 1024];
        URI fileUri = runContext.storage().putFile(new ByteArrayInputStream(largeContent), "large-file.pdf");

        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue("large-file.pdf"))
            .build();

        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        var mockCreateUploadSession = mock(CreateUploadSessionRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.createUploadSession()).thenReturn(mockCreateUploadSession);
        when(mockCreateUploadSession.post(any())).thenReturn(null);

        Upload testTask = spy(uploadTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When / Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> testTask.run(runContext));
        assertThat(exception.getMessage(), containsString("no upload URL was returned"));
    }

    @Test
    void shouldFailWhenUploadSessionHasNoUploadUrl() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);

        byte[] largeContent = new byte[5 * 1024 * 1024];
        URI fileUri = runContext.storage().putFile(new ByteArrayInputStream(largeContent), "large-file.pdf");

        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue("large-file.pdf"))
            .build();

        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        var mockCreateUploadSession = mock(CreateUploadSessionRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.createUploadSession()).thenReturn(mockCreateUploadSession);
        when(mockCreateUploadSession.post(any())).thenReturn(new UploadSession()); // uploadUrl left unset

        Upload testTask = spy(uploadTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));

        // When / Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> testTask.run(runContext));
        assertThat(exception.getMessage(), containsString("no upload URL was returned"));
    }

    @Test
    void shouldFailWhenResumableUploadResultIsUnsuccessful() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();
        SharepointConnection mockConnection = mock(SharepointConnection.class);
        GraphServiceClient mockClient = mock(GraphServiceClient.class);

        byte[] largeContent = new byte[5 * 1024 * 1024];
        URI fileUri = runContext.storage().putFile(new ByteArrayInputStream(largeContent), "large-file.pdf");

        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue("large-file.pdf"))
            .build();

        when(mockConnection.createClient(any())).thenReturn(mockClient);
        when(mockConnection.getDriveId(any(), any())).thenReturn("test-drive-id");

        DrivesRequestBuilder mockDrives = mock(DrivesRequestBuilder.class);
        DriveItemRequestBuilder mockDriveItems = mock(DriveItemRequestBuilder.class);
        DriveItemItemRequestBuilder mockDriveItem = mock(DriveItemItemRequestBuilder.class);
        var mockCreateUploadSession = mock(CreateUploadSessionRequestBuilder.class);

        when(mockClient.drives()).thenReturn(mockDrives);
        when(mockDrives.byDriveId(anyString())).thenReturn(mockDriveItems);
        when(mockDriveItems.items()).thenReturn(mock(ItemsRequestBuilder.class));
        when(mockDriveItems.items().byDriveItemId(anyString())).thenReturn(mockDriveItem);
        when(mockDriveItem.createUploadSession()).thenReturn(mockCreateUploadSession);

        var uploadSession = new UploadSession();
        uploadSession.setUploadUrl("https://contoso.sharepoint.com/_api/upload-session/abc");
        uploadSession.setNextExpectedRanges(List.of("0-" + (largeContent.length - 1)));
        uploadSession.setExpirationDateTime(OffsetDateTime.now().plusMinutes(10));
        when(mockCreateUploadSession.post(any())).thenReturn(uploadSession);

        // Only performUpload is stubbed: uploadInChunks runs for real and must surface the failure itself
        Upload testTask = spy(uploadTask);
        doReturn(mockConnection).when(testTask).connection(any(RunContext.class));
        doReturn(null).when(testTask).performUpload(any());

        // When / Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> testTask.run(runContext));
        assertThat(exception.getMessage(), containsString("did not complete successfully"));
    }

    @Test
    void shouldRejectChunkSizeNotMultipleOf320KiB() throws Exception {
        // Given: chunkSize is validated before any Graph call is made, so no connection mocking is needed
        RunContext runContext = runContextFactory.of();
        URI fileUri = runContext.storage().putFile(new ByteArrayInputStream("small file content".getBytes()), "small.txt");

        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue("small.txt"))
            .chunkSize(Property.ofValue(1_000L)) // not a multiple of 320 KiB (327,680 bytes)
            .build();

        // When / Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> uploadTask.run(runContext));
        assertThat(exception.getMessage(), containsString("320 KiB"));
    }

    @Test
    void shouldRejectChunkSizeAboveMaximum() throws Exception {
        // Given: 192 * 320 KiB = exactly 60 MiB, a valid multiple of 320 KiB but the Graph limit is "less than 60 MiB"
        RunContext runContext = runContextFactory.of();
        URI fileUri = runContext.storage().putFile(new ByteArrayInputStream("small file content".getBytes()), "small.txt");

        Upload uploadTask = Upload.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .siteId(Property.ofValue("test-site-id"))
            .driveId(Property.ofValue("test-drive-id"))
            .from(Property.ofValue(fileUri.toString()))
            .to(Property.ofValue("small.txt"))
            .chunkSize(Property.ofValue(327_680L * 192)) // exactly 60 MiB: valid alignment, but the Graph limit is exclusive
            .build();

        // When / Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> uploadTask.run(runContext));
        assertThat(exception.getMessage(), containsString("60 MiB"));
    }

    @Test
    void shouldSendChunkedPutRequestsMatchingConfiguredChunkSize() throws Exception {
        // Given: a 2-chunk upload (655,360 bytes at a 320 KiB chunk size), served entirely by WireMock
        long chunkSize = 327_680L; // 320 KiB, the minimum valid chunk size
        long fileSize = chunkSize * 2;
        String uploadPath = "/upload-session/large-file";

        wireMock.stubFor(put(urlPathEqualTo(uploadPath))
            .withHeader("Content-Range", equalTo("bytes 0-327679/655360"))
            .willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"nextExpectedRanges\":[\"327680-655359\"]}")));

        wireMock.stubFor(put(urlPathEqualTo(uploadPath))
            .withHeader("Content-Range", equalTo("bytes 327680-655359/655360"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"large-file-id\",\"name\":\"large-file.pdf\"," +
                    "\"webUrl\":\"https://contoso.sharepoint.com/large-file.pdf\",\"size\":655360}")));

        UploadSession uploadSession = new UploadSession();
        uploadSession.setUploadUrl(wireMock.baseUrl() + uploadPath);
        uploadSession.setNextExpectedRanges(List.of("0-" + (fileSize - 1)));
        uploadSession.setExpirationDateTime(OffsetDateTime.now().plusMinutes(10));

        RunContext runContext = runContextFactory.of();
        Upload uploadTask = Upload.builder().build();

        // When
        DriveItem result;
        try (InputStream fileStream = new ByteArrayInputStream(new byte[(int) fileSize])) {
            result = uploadTask.uploadInChunks(runContext, uploadSession, fileStream, fileSize, chunkSize);
        }

        // Then
        assertThat(result.getId(), is("large-file-id"));
        assertThat(result.getName(), is("large-file.pdf"));
        assertThat(result.getSize(), is(fileSize));

        wireMock.verify(2, putRequestedFor(urlPathEqualTo(uploadPath)));
        wireMock.verify(putRequestedFor(urlPathEqualTo(uploadPath))
            .withHeader("Content-Range", equalTo("bytes 0-327679/655360"))
            .withHeader("Content-Length", equalTo(String.valueOf(chunkSize))));
        wireMock.verify(putRequestedFor(urlPathEqualTo(uploadPath))
            .withHeader("Content-Range", equalTo("bytes 327680-655359/655360"))
            .withHeader("Content-Length", equalTo(String.valueOf(chunkSize))));
    }

    @Test
    void shouldDeleteUploadSessionOnKill() throws Exception {
        // Given: an upload session that completed but is still trackable on the server
        long chunkSize = 327_680L;
        long fileSize = chunkSize;
        String uploadPath = "/upload-session/kill-test";

        wireMock.stubFor(put(urlPathEqualTo(uploadPath))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"file-id\",\"name\":\"file.bin\"," +
                    "\"webUrl\":\"https://contoso.sharepoint.com/file.bin\",\"size\":327680}")));
        wireMock.stubFor(delete(urlPathEqualTo(uploadPath))
            .willReturn(aResponse().withStatus(204)));

        UploadSession uploadSession = new UploadSession();
        uploadSession.setUploadUrl(wireMock.baseUrl() + uploadPath);
        uploadSession.setNextExpectedRanges(List.of("0-" + (fileSize - 1)));
        uploadSession.setExpirationDateTime(OffsetDateTime.now().plusMinutes(10));

        RunContext runContext = runContextFactory.of();
        Upload uploadTask = Upload.builder().build();

        try (InputStream fileStream = new ByteArrayInputStream(new byte[(int) fileSize])) {
            uploadTask.uploadInChunks(runContext, uploadSession, fileStream, fileSize, chunkSize);
        }

        // When
        uploadTask.kill();
        uploadTask.kill(); // idempotent: a second kill signal must not send a second DELETE

        // Then
        wireMock.verify(1, deleteRequestedFor(urlPathEqualTo(uploadPath)));
    }
}
