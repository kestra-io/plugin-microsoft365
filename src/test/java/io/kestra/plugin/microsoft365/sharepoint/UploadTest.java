package io.kestra.plugin.microsoft365.sharepoint;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import org.junit.jupiter.api.Test;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.is;

/**
 * This test will only test the main task, this allow you to send any input
 * parameters to your task and test the returning behaviour easily.
 */
@KestraTest
class UploadTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of());

        Upload task = Upload.builder()
            .siteId(new Property<>("test-site-id"))
            .driveId(new Property<>("test-drive-id"))
            .parentId(new Property<>("test-parent-id"))
            .filename(new Property<>("test-file.txt"))
            .content(new Property<>("Test content to upload"))
            .build();

        Upload.Output runOutput = task.run(runContext);

        assertThat(runOutput, notNullValue());
        assertThat(runOutput.getItemId(), notNullValue());
        assertThat(runOutput.getItemName(), notNullValue());
        assertThat(runOutput.getUri(), notNullValue());
        assertThat(runOutput.getUploaded(), notNullValue());
    }
}
