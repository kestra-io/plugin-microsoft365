package io.kestra.plugin.microsoft365.defender;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.State;
import io.kestra.core.repositories.LocalFlowRepositoryLoader;
import io.kestra.core.runners.TestRunner;
import io.kestra.core.runners.TestRunnerUtils;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.storages.StorageInterface;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.kestra.core.tenant.TenantService.MAIN_TENANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class TriggerEngineTest extends AbstractDefenderTest {
    @Inject
    private LocalFlowRepositoryLoader repositoryLoader;

    @Inject
    private TestRunner runner;

    @Inject
    private TestRunnerUtils runnerUtils;

    @Inject
    private StorageInterface storage;

    @Test
    void schedulerFiresAndRunsFlowWithStoredAlerts() throws Exception {
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .withQueryParam("$filter", containing("lastUpdateDateTime ge "))
            .willReturn(okJson("{\"value\":["
                + alertJson("engine-alert", "high", "new", Instant.now().toString()) + "]}")));

        repositoryLoader.load(Objects.requireNonNull(getClass().getClassLoader().getResource("flows/defender")));
        runner.setWorkerThread(2);
        runner.run();

        // The real scheduler initializes the state on its first poll, then polls Graph through the SDK.
        var execution = runnerUtils.awaitFlowExecution(
            candidate -> candidate.getState().isTerminated(),
            MAIN_TENANT, "io.kestra.tests.defender", "defender-trigger", Duration.ofSeconds(30)
        );

        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
        assertThat(execution.getTrigger().getId(), is("watch"));
        assertThat(execution.getTrigger().getVariables().get("count"), is(1));
        assertThat(execution.getTrigger().getVariables(), not(hasKey("items")));
        assertThat(execution.getTaskRunList(), hasSize(1));
        assertThat(execution.getTaskRunList().getFirst().getOutputs().get("value"), is("1"));

        var uri = URI.create(execution.getTrigger().getVariables().get("uri").toString());
        try (var input = storage.get(execution.getTenantId(), execution.getNamespace(), uri)) {
            var items = FileSerde.readAll(input).collectList().block();
            assertThat(items, hasSize(1));
            assertThat(((Map<?, ?>) items.getFirst()).get("id"), is("engine-alert"));
        }
        wm.verify(getRequestedFor(urlPathEqualTo(GRAPH + "/security/alerts_v2")));
    }
}
