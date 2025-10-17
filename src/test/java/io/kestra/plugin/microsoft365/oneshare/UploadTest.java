package io.kestra.plugin.microsoft365.oneshare;

import com.devskiller.friendly_id.FriendlyId;
import com.google.common.collect.ImmutableMap;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.utils.TestsUtils;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.util.Objects;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
class UploadTest extends AbstractOneShareTest {
    @Inject
    private OnesShareTestUtils testUtils;

    @Value("${kestra.tasks.oneshare.tenantId}")
    private String tenantId;
    @Value("${kestra.tasks.oneshare.clientId}")
    private String clientId;
    @Value("${kestra.tasks.oneshare.clientSecret}")
    private String clientSecret;
    @Value("${kestra.tasks.oneshare.driveId}")
    private String driveId;

    @Test
    void smallFileUpload() throws Exception {
        // Test small file upload (should use simple PUT content method)
        String fileName = FriendlyId.createFriendlyId() + ".yml";
        Upload.Output run = testUtils.upload("Documents/TestUpload", fileName);

        assertThat(run.getFile(), notNullValue());
        assertThat(run.getFile().getName(), is(fileName));
        assertThat(run.getFile().getId(), notNullValue());
        assertThat(run.getFile().getSize(), greaterThan(0L));
        assertThat(run.getFile().isFolder(), is(false));
    }

    @Test
    void uploadToRootFolder() throws Exception {
        // Test uploading to root directory
        File file = new File(Objects.requireNonNull(UploadTest.class.getClassLoader()
            .getResource("application.yml"))
            .toURI());

        URI source = storageInterface.put(
            TenantService.MAIN_TENANT,
            null,
            new URI("/" + FriendlyId.createFriendlyId()),
            new FileInputStream(file)
        );

        String fileName = "root-upload-" + FriendlyId.createFriendlyId() + ".yml";

        Upload task = Upload.builder()
            .id(UploadTest.class.getSimpleName())
            .type(Upload.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root"))
            .fileName(Property.ofValue(fileName))
            .from(Property.ofValue(source.toString()))
            .build();

        Upload.Output run = task.run(runContext(task));

        assertThat(run.getFile(), notNullValue());
        assertThat(run.getFile().getName(), is(fileName));
        assertThat(run.getFile().getId(), notNullValue());
    }

    @Test
    void uploadWithConflictBehaviorReplace() throws Exception {
        // Upload a file
        String fileName = "conflict-test-" + FriendlyId.createFriendlyId() + ".txt";
        String content1 = "Original content";
        
        URI source1 = createTestFile(content1);

        Upload task1 = Upload.builder()
            .id(UploadTest.class.getSimpleName())
            .type(Upload.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root:/Documents/TestUpload"))
            .fileName(Property.ofValue(fileName))
            .from(Property.ofValue(source1.toString()))
            .conflictBehavior(Property.ofValue(Upload.ConflictBehavior.REPLACE))
            .build();

        Upload.Output run1 = task1.run(runContext(task1));
        assertThat(run1.getFile().getId(), notNullValue());

        // Upload again with same name - should replace
        String content2 = "Replaced content";
        URI source2 = createTestFile(content2);

        Upload task2 = Upload.builder()
            .id(UploadTest.class.getSimpleName())
            .type(Upload.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root:/Documents/TestUpload"))
            .fileName(Property.ofValue(fileName))
            .from(Property.ofValue(source2.toString()))
            .conflictBehavior(Property.ofValue(Upload.ConflictBehavior.REPLACE))
            .build();

        Upload.Output run2 = task2.run(runContext(task2));

        // Verify file was replaced (same name, could be same or different ID depending on API behavior)
        assertThat(run2.getFile().getName(), is(fileName));
        assertThat(run2.getFile().getSize(), is((long) content2.length()));
    }

    @Test
    void uploadWithConflictBehaviorRename() throws Exception {
        // Upload a file
        String fileName = "rename-test-" + FriendlyId.createFriendlyId() + ".txt";
        String content = "Test content";
        
        URI source1 = createTestFile(content);

        Upload task1 = Upload.builder()
            .id(UploadTest.class.getSimpleName())
            .type(Upload.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root:/Documents/TestUpload"))
            .fileName(Property.ofValue(fileName))
            .from(Property.ofValue(source1.toString()))
            .build();

        task1.run(runContext(task1));

        // Upload again with same name and RENAME conflict behavior
        URI source2 = createTestFile(content);

        Upload task2 = Upload.builder()
            .id(UploadTest.class.getSimpleName())
            .type(Upload.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root:/Documents/TestUpload"))
            .fileName(Property.ofValue(fileName))
            .from(Property.ofValue(source2.toString()))
            .conflictBehavior(Property.ofValue(Upload.ConflictBehavior.RENAME))
            .build();

        Upload.Output run2 = task2.run(runContext(task2));

        // Verify file was renamed (name should be different)
        assertThat(run2.getFile().getName(), not(is(fileName)));
        assertThat(run2.getFile().getName(), containsString(fileName.replace(".txt", "")));
    }

    @Test
    void largeFileUpload() throws Exception {
        // Test large file upload (should use resumable upload session)
        // Create a file larger than 4MB threshold
        int fileSizeInMB = 5;
        byte[] largeContent = new byte[fileSizeInMB * 1024 * 1024];
        // Fill with some pattern data
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }

        URI source = storageInterface.put(
            TenantService.MAIN_TENANT,
            null,
            new URI("/" + FriendlyId.createFriendlyId()),
            new ByteArrayInputStream(largeContent)
        );

        String fileName = "large-file-" + FriendlyId.createFriendlyId() + ".bin";

        Upload task = Upload.builder()
            .id(UploadTest.class.getSimpleName())
            .type(Upload.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root:/Documents/TestUpload"))
            .fileName(Property.ofValue(fileName))
            .from(Property.ofValue(source.toString()))
            .largeFileThreshold(Property.ofValue(4 * 1024 * 1024L)) // 4MB
            .maxSliceSize(Property.ofValue(3 * 1024 * 1024)) // 3MB chunks
            .build();

        Upload.Output run = task.run(runContext(task));

        assertThat(run.getFile(), notNullValue());
        assertThat(run.getFile().getName(), is(fileName));
        assertThat(run.getFile().getSize(), is((long) largeContent.length));
        assertThat(run.getFile().getId(), notNullValue());
    }

    @Test
    void uploadWithCustomThreshold() throws Exception {
        // Test upload with custom threshold (force small file to use resumable upload)
        File file = new File(Objects.requireNonNull(UploadTest.class.getClassLoader()
            .getResource("application.yml"))
            .toURI());

        URI source = storageInterface.put(
            TenantService.MAIN_TENANT,
            null,
            new URI("/" + FriendlyId.createFriendlyId()),
            new FileInputStream(file)
        );

        String fileName = "custom-threshold-" + FriendlyId.createFriendlyId() + ".yml";

        Upload task = Upload.builder()
            .id(UploadTest.class.getSimpleName())
            .type(Upload.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root:/Documents/TestUpload"))
            .fileName(Property.ofValue(fileName))
            .from(Property.ofValue(source.toString()))
            .largeFileThreshold(Property.ofValue(1L)) // 1 byte - force resumable upload
            .maxSliceSize(Property.ofValue(327680)) // Minimum valid chunk size (320 KiB)
            .maxRetryAttempts(Property.ofValue(3))
            .build();

        Upload.Output run = task.run(runContext(task));

        assertThat(run.getFile(), notNullValue());
        assertThat(run.getFile().getName(), is(fileName));
        assertThat(run.getFile().getId(), notNullValue());
    }

    private URI createTestFile(String content) throws Exception {
        return storageInterface.put(
            TenantService.MAIN_TENANT,
            null,
            new URI("/" + FriendlyId.createFriendlyId()),
            new ByteArrayInputStream(content.getBytes())
        );
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
