package io.kestra.plugin.microsoft365.oneshare;

import com.devskiller.friendly_id.FriendlyId;
import com.google.common.collect.ImmutableMap;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.TestsUtils;
import io.micronaut.context.annotation.Value;
import org.junit.jupiter.api.Test;

import static io.kestra.core.tenant.TenantService.MAIN_TENANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
class CreateTest extends AbstractOneShareTest {

    @Value("${kestra.tasks.oneshare.tenantId}")
    private String tenantId;
    @Value("${kestra.tasks.oneshare.clientId}")
    private String clientId;
    @Value("${kestra.tasks.oneshare.clientSecret}")
    private String clientSecret;
    @Value("${kestra.tasks.oneshare.driveId}")
    private String driveId;

    @Test
    void createFolder() throws Exception {
        String folderName = "test-folder-" + FriendlyId.createFriendlyId();

        Create task = Create.builder()
            .id(CreateTest.class.getSimpleName())
            .type(Create.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root:/Documents/TestCreate"))
            .name(Property.ofValue(folderName))
            .folder(Property.ofValue(true))
            .build();

        Create.Output runOutput = task.run(runContext(task));

        assertThat(runOutput.getFile().isFolder(), is(true));
        assertThat(runOutput.getFile().getName(), is(folderName));
        assertThat(runOutput.getFile().getId(), notNullValue());
    }

    @Test
    void createFile() throws Exception {
        String fileName = "test-file-" + FriendlyId.createFriendlyId() + ".txt";
        String content = "Hello World from Kestra!";

        Create task = Create.builder()
            .id(CreateTest.class.getSimpleName())
            .type(Create.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root:/Documents/TestCreate"))
            .name(Property.ofValue(fileName))
            .content(Property.ofValue(content))
            .build();

        Create.Output runOutput = task.run(runContext(task));

        assertThat(runOutput.getFile().isFolder(), is(false));
        assertThat(runOutput.getFile().getName(), is(fileName));
        assertThat(runOutput.getFile().getId(), notNullValue());

        // Verify file can be downloaded with the correct content
        Download downloadTask = Download.builder()
            .id(DownloadTest.class.getSimpleName())
            .type(Download.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .itemId(Property.ofValue(runOutput.getFile().getId()))
            .build();

        Download.Output downloadOutput = downloadTask.run(runContext(downloadTask));
        String downloadedContent = new String(storageInterface.get(
            MAIN_TENANT,
            null,
            downloadOutput.getUri()
        ).readAllBytes());

        assertThat(downloadedContent, is(content));
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
