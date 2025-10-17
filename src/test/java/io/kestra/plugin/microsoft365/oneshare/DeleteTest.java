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
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.util.Objects;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
class DeleteTest extends AbstractOneShareTest {

    @Value("${kestra.tasks.oneshare.tenantId}")
    private String tenantId;
    @Value("${kestra.tasks.oneshare.clientId}")
    private String clientId;
    @Value("${kestra.tasks.oneshare.clientSecret}")
    private String clientSecret;
    @Value("${kestra.tasks.oneshare.driveId}")
    private String driveId;

    @Test
    void fromStorage() throws Exception {
        File file = new File(Objects.requireNonNull(DeleteTest.class.getClassLoader()
            .getResource("application.yml"))
            .toURI());

        URI source = storageInterface.put(
            TenantService.MAIN_TENANT,
            null,
            new URI("/" + FriendlyId.createFriendlyId()),
            new FileInputStream(file)
        );

        String fileName = FriendlyId.createFriendlyId() + ".yml";

        Upload upload = Upload.builder()
            .id(UploadTest.class.getSimpleName())
            .type(Upload.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root:/Documents/TestDelete"))
            .fileName(Property.ofValue(fileName))
            .from(Property.ofValue(source.toString()))
            .build();

        Upload.Output uploadOutput = upload.run(runContext(upload));

        Delete task = Delete.builder()
            .id(DeleteTest.class.getSimpleName())
            .type(Delete.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .itemId(Property.ofValue(uploadOutput.getFile().getId()))
            .build();

        // First delete should succeed (file exists)
        task.run(runContext(task));

        // Verify file was deleted by trying to download it
        Download downloadTask = Download.builder()
            .id(DownloadTest.class.getSimpleName())
            .type(Download.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .itemId(Property.ofValue(uploadOutput.getFile().getId()))
            .build();

        // This should throw an exception since file is deleted
        try {
            downloadTask.run(runContext(downloadTask));
            assertThat("Expected exception for deleted file", false);
        } catch (Exception e) {
            // Expected - file should not be found
            assertThat(true, is(true));
        }
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
