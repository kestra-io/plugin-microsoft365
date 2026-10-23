package io.kestra.plugin.microsoft365.teams;


import com.google.common.io.Files;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.utils.IdUtils;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@KestraTest
class TeamsIncomingWebhookTest {
    @Inject
    private ApplicationContext applicationContext;

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private ModelValidator modelValidator;

    @Test
    void run() throws Exception {
        RunContext runContext = runContextFactory.of(
            Map.of(
                "activityTitle", "Teams notification",
                "activitySubtitle", "Teams notification from Kestra"
            )
        );

        EmbeddedServer embeddedServer = applicationContext.getBean(EmbeddedServer.class);
        embeddedServer.start();

        TeamsIncomingWebhook task = TeamsIncomingWebhook.builder()
            .url(embeddedServer.getURI() + "/webhook-unit-test")
            .payload(Property.ofExpression(
                Files.asCharSource(
                    new File(Objects.requireNonNull(TeamsIncomingWebhookTest.class.getClassLoader()
                            .getResource("teams.peb"))
                        .toURI()),
                    StandardCharsets.UTF_8
                ).read())
            )
            .build();

        task.run(runContext);

        assertThat(FakeWebhookController.data, containsString("Teams notification from Kestra"));
    }

    @SuppressWarnings("deprecation")
    @Test
    void flowSettingDeprecatedOptionsStillValidatesAndRuns() throws Exception {
        // Flows written before the deprecation still set these options: they must keep parsing, validating and running.
        var message = "deprecated-options-" + IdUtils.create();
        var embeddedServer = applicationContext.getBean(EmbeddedServer.class);
        embeddedServer.start();

        var flow = YamlParser.parse(
            """
                id: teams_deprecated_options
                namespace: io.kestra.tests
                tasks:
                  - id: send
                    type: io.kestra.plugin.microsoft365.teams.TeamsIncomingWebhook
                    url: "%s/webhook-unit-test"
                    payload: '{"text": "%s"}'
                    options:
                      readTimeout: PT30S
                      connectionPoolIdleTimeout: PT10S
                      maxContentLength: 1024
                """.formatted(embeddedServer.getURI(), message),
            Flow.class
        );

        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));

        var runContext = runContextFactory.of(Map.of());
        var task = (TeamsIncomingWebhook) flow.getTasks().getFirst();
        var options = task.getOptions();
        assertThat(runContext.render(options.getReadTimeout()).as(Duration.class).orElseThrow(), is(Duration.ofSeconds(30)));
        assertThat(runContext.render(options.getConnectionPoolIdleTimeout()).as(Duration.class).orElseThrow(), is(Duration.ofSeconds(10)));
        assertThat(runContext.render(options.getMaxContentLength()).as(Integer.class).orElseThrow(), is(1024));

        task.run(runContext);

        assertThat(FakeWebhookController.data, containsString(message));
    }
}