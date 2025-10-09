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
class ExportTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of());

        Export task = Export.builder()
            .siteId(new Property<>("test-site-id"))
            .driveId(new Property<>("test-drive-id"))
            .itemId(new Property<>("test-item-id"))
            .format(new Property<>("pdf"))
            .build();

        Export.Output runOutput = task.run(runContext);

        assertThat(runOutput, notNullValue());
        assertThat(runOutput.getItemId(), is("test-item-id"));
        assertThat(runOutput.getUri(), notNullValue());
        assertThat(runOutput.getContent(), notNullValue());
        assertThat(runOutput.getFormat(), is("pdf"));
    }
}
