package io.kestra.plugin.microsoft365.oneshare;

import com.devskiller.friendly_id.FriendlyId;
import com.google.common.collect.ImmutableMap;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.utils.TestsUtils;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@KestraTest
class ListFilesTest extends AbstractOneShareTest {
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
    void run() throws Exception {
        String dir = FriendlyId.createFriendlyId();

        // Create parent folder
        Create createFolder = Create.builder()
            .id(CreateTest.class.getSimpleName())
            .type(Create.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue("root:/Documents/TestListFiles"))
            .name(Property.ofValue(dir))
            .folder(Property.ofValue(true))
            .build();
        
        Create.Output folderOutput = createFolder.run(TestsUtils.mockRunContext(
            this.runContextFactory,
            createFolder,
            ImmutableMap.of(
                "tenantId", tenantId,
                "clientId", clientId,
                "clientSecret", clientSecret,
                "driveId", driveId
            )
        ));

        // Upload 5 files to test directory
        for (int i = 0; i < 5; i++) {
            String fileName = FriendlyId.createFriendlyId() + ".yml";
            testUtils.upload("Documents/TestListFiles/" + dir, fileName);
        }

        // List files in the folder
        ListFiles task = task()
            .itemId(Property.ofValue(folderOutput.getFile().getId()))
            .build();
        
        ListFiles.Output run = task.run(TestsUtils.mockRunContext(
            this.runContextFactory,
            task,
            ImmutableMap.of(
                "tenantId", tenantId,
                "clientId", clientId,
                "clientSecret", clientSecret,
                "driveId", driveId
            )
        ));
        
        assertThat(run.getFiles().size(), greaterThanOrEqualTo(5));

        // Test listing root folder
        ListFiles rootTask = task()
            .itemId(Property.ofValue("root"))
            .build();
        
        ListFiles.Output rootRun = rootTask.run(TestsUtils.mockRunContext(
            this.runContextFactory,
            rootTask,
            ImmutableMap.of(
                "tenantId", tenantId,
                "clientId", clientId,
                "clientSecret", clientSecret,
                "driveId", driveId
            )
        ));
        
        assertThat(rootRun.getFiles().size(), greaterThanOrEqualTo(1));
    }

    private static ListFiles.ListFilesBuilder<?, ?> task() {
        return ListFiles.builder()
            .id(ListFilesTest.class.getSimpleName())
            .type(ListFiles.class.getName())
            .tenantId(Property.ofValue("{{ inputs.tenantId }}"))
            .clientId(Property.ofValue("{{ inputs.clientId }}"))
            .clientSecret(Property.ofValue("{{ inputs.clientSecret }}"))
            .driveId(Property.ofValue("{{ inputs.driveId }}"));
    }
}