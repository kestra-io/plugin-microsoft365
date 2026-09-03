package io.kestra.plugin.microsoft365;

import com.microsoft.graph.core.models.UploadResult;
import com.microsoft.graph.core.tasks.LargeFileUploadTask;
import com.microsoft.graph.drives.item.items.item.createuploadsession.CreateUploadSessionPostRequestBody;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemUploadableProperties;
import com.microsoft.graph.models.UploadSession;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.runners.RunContext;

import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Microsoft Graph resumable upload session plumbing shared by the {@code oneshare} and {@code sharepoint}
 * Upload tasks: session creation, the chunked transfer, and killing a session so it is not orphaned server-side.
 *
 * <p>An instance holds the kill state of the one session its owning task created, so each task instance must own
 * its own instance and forward {@code kill()} to it.
 *
 * @see <a href="https://learn.microsoft.com/en-us/graph/api/driveitem-createuploadsession">driveItem: createUploadSession</a>
 */
public class GraphResumableUpload {

    /**
     * Runs the chunked transfer of an already-built {@link LargeFileUploadTask}. Tasks supply their own so they can
     * pass the retry count and progress callback they expose, and so tests can stub the transfer itself.
     */
    @FunctionalInterface
    public interface Transfer {
        UploadResult<DriveItem> execute(LargeFileUploadTask<DriveItem> task) throws Exception;
    }

    // Set once the upload session exists so kill() can free the remote resource; the transfer and kill() may run on different threads.
    private final AtomicReference<Runnable> killable = new AtomicReference<>();

    private final AtomicBoolean killed = new AtomicBoolean(false);

    /**
     * Creates an upload session for {@code itemPath}, requesting the given
     * <a href="https://learn.microsoft.com/en-us/graph/api/driveitem-createuploadsession#request-body">conflict behavior</a>
     * ({@code fail}, {@code replace} or {@code rename}).
     */
    public static UploadSession createSession(GraphServiceClient client, String driveId, String itemPath, String conflictBehavior) {
        var properties = new DriveItemUploadableProperties();
        properties.getAdditionalData().put("@microsoft.graph.conflictBehavior", conflictBehavior);

        var requestBody = new CreateUploadSessionPostRequestBody();
        requestBody.setItem(properties);

        return client.drives().byDriveId(driveId)
            .items().byDriveItemId(itemPath)
            .createUploadSession()
            .post(requestBody);
    }

    /**
     * Uploads {@code fileStream} to the session's upload URL in {@code chunkSize} byte ranges, registering the session
     * for teardown by {@link #kill()} first.
     */
    public DriveItem upload(RunContext runContext, UploadSession uploadSession, InputStream fileStream,
                            long fileSize, long chunkSize, Transfer transfer) throws Exception {
        // requestAdapter is null on purpose: the upload URL is pre-authenticated, and Graph documents that sending an
        // Authorization header on the chunk PUTs "might result in an HTTP 401 Unauthorized response", so the SDK must
        // build an anonymous adapter pointed at the upload URL rather than reuse the authenticated Graph client.
        // See https://learn.microsoft.com/en-us/graph/api/driveitem-createuploadsession#upload-bytes-to-the-upload-session
        LargeFileUploadTask<DriveItem> largeFileUploadTask = new LargeFileUploadTask<>(
            null,
            uploadSession,
            fileStream,
            fileSize,
            chunkSize,
            DriveItem::createFromDiscriminatorValue
        );

        // A kill() landing before the task exists would find killable empty, so re-check once it is set.
        killable.set(() -> {
            try {
                largeFileUploadTask.deleteSession();
            } catch (Exception e) {
                runContext.logger().warn("Failed to delete the Microsoft Graph upload session after kill: {}", e.getMessage(), e);
            }
        });
        if (killed.get()) {
            killable.get().run();
            throw new InterruptedException("Upload of '" + uploadSession.getUploadUrl() + "' was killed before it started");
        }

        var result = transfer.execute(largeFileUploadTask);
        if (result == null || !result.isUploadSuccessful() || result.itemResponse == null) {
            throw new IllegalStateException("Resumable upload did not complete successfully via the Microsoft Graph API");
        }

        return result.itemResponse;
    }

    /**
     * Cancels the upload session, if one was created, by sending a DELETE to its upload URL. Idempotent: only the
     * first call reaches Graph.
     */
    public void kill() {
        if (killed.compareAndSet(false, true)) {
            Optional.ofNullable(killable.get()).ifPresent(Runnable::run);
        }
    }
}
