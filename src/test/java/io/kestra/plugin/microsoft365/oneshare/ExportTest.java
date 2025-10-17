package io.kestra.plugin.microsoft365.oneshare;

import com.devskiller.friendly_id.FriendlyId;
import com.google.common.collect.ImmutableMap;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.TestsUtils;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static io.kestra.core.tenant.TenantService.MAIN_TENANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
class ExportTest extends AbstractOneShareTest {
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
        // First, upload a file to export
        String fileName = FriendlyId.createFriendlyId() + ".yml";
        Upload.Output uploadOutput = testUtils.upload("Documents/TestExport", fileName);

        Export task = Export.builder()
            .id(ExportTest.class.getSimpleName())
            .type(Export.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .itemId(Property.ofValue(uploadOutput.getFile().getId()))
            .format(Property.ofValue("pdf"))
            .build();

        Export.Output runOutput = task.run(runContext(task));

        // Verify content was exported
        assertThat(runOutput.getUri(), notNullValue());
        InputStream exportedContent = storageInterface.get(
            MAIN_TENANT,
            null,
            runOutput.getUri()
        );
        assertThat(exportedContent, notNullValue());
        
        // Verify we can read the exported content
        byte[] content = exportedContent.readAllBytes();
        assertThat(content.length > 0, notNullValue());
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
