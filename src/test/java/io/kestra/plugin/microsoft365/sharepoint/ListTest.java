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
class ListTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of());

        List task = List.builder()
            .siteId(new Property<>("test-site-id"))
            .driveId(new Property<>("test-drive-id"))
            .itemId(new Property<>("test-folder-id"))
            .build();

        List.Output runOutput = task.run(runContext);

        assertThat(runOutput, notNullValue());
        assertThat(runOutput.getSiteId(), notNullValue());
        assertThat(runOutput.getDriveId(), notNullValue());
        assertThat(runOutput.getUri(), notNullValue());
        assertThat(runOutput.getItems(), notNullValue());
    }
}
