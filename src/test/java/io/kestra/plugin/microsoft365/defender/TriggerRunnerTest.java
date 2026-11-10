package io.kestra.plugin.microsoft365.defender;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.triggers.StatefulTriggerService;
import io.kestra.core.repositories.ExecutionRepositoryInterface;
import io.kestra.core.repositories.LocalFlowRepositoryLoader;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.TestRunnerUtils;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.utils.Await;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.kestra.core.tenant.TenantService.MAIN_TENANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest(startRunner = true, startScheduler = true)
class TriggerRunnerTest extends AbstractDefenderTest {
    private static final String NAMESPACE = "io.kestra.tests.defender";
    private static final String FLOW_ID = "defender-trigger-runner";
    private static final String STATE_KEY = "defender_runner_state";

    @Inject
    private LocalFlowRepositoryLoader repositoryLoader;

    @Inject
    private TestRunnerUtils runnerUtils;

    @Inject
    private ExecutionRepositoryInterface executionRepository;

    @Test
    void schedulerFiresAFlowWithStoredAlertsAndDoesNotReplayThem() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of("flow", Map.of(
            "tenantId", MAIN_TENANT, "namespace", NAMESPACE, "id", FLOW_ID)));
        Instant watermark = Instant.parse("2026-10-24T08:00:00Z");
        Map<String, StatefulTriggerService.Entry> state = new HashMap<>();
        state.put(Trigger.WATERMARK_KEY, new StatefulTriggerService.Entry(
            Trigger.WATERMARK_KEY, watermark.toString(), watermark, Instant.now()));
        StatefulTriggerService.writeState(runContext, STATE_KEY, state, Optional.empty());
        assertThat(StatefulTriggerService.readState(runContext, STATE_KEY, Optional.empty())
            .get(Trigger.WATERMARK_KEY).version(), is(watermark.toString()));

        String nextLink = wm.baseUrl() + GRAPH + "/security/alerts_v2?$skiptoken=p2";
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .withQueryParam("$skiptoken", equalTo("p2"))
            .atPriority(1)
            .willReturn(okJson("{\"value\":[" + alertJson("a2", "high", "new", "2026-10-24T09:00:00Z") + "]}")));
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .withQueryParam("$filter", containing("lastUpdateDateTime ge"))
            .atPriority(2)
            .willReturn(okJson("{\"value\":[" + alertJson("a1", "high", "new", "2026-10-24T09:00:00Z")
                + "],\"@odata.nextLink\":\"" + nextLink + "\"}")));

        // Only authentication and the Graph base URL are replaced by TestableTrigger.
        // The scheduler, trigger worker, execution queue, executor and task worker are real.
        repositoryLoader.load(Objects.requireNonNull(getClass().getClassLoader().getResource("flows/defender-runner")));
        Execution execution = runnerUtils.awaitFlowExecution(
            candidate -> candidate.getState().isTerminated(), MAIN_TENANT, NAMESPACE, FLOW_ID, Duration.ofSeconds(30));

        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
        assertThat(execution.getTrigger().getId(), is("on_alert"));
        Map<String, Object> variables = execution.getTrigger().getVariables();
        assertThat(variables.get("count"), is(2));
        assertThat(variables, not(hasKey("items")));
        URI uri = URI.create(variables.get("uri").toString());
        try (InputStream input = runContext.storage().getFile(uri)) {
            List<Object> items = FileSerde.readAll(input).collectList().block();
            assertThat(items.stream().map(item -> ((Map<?, ?>) item).get("id")).toList(), contains("a1", "a2"));
        }
        assertThat(execution.getTaskRunList(), hasSize(1));
        assertThat(execution.getTaskRunList().getFirst().getOutputs().get("value"), is("2|" + uri));

        // Wait for another complete poll against the same Graph data before checking deduplication.
        Await.until(() -> wm.findAll(getRequestedFor(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
                .withQueryParam("$skiptoken", equalTo("p2"))).size() >= 3,
            Duration.ofMillis(100), Duration.ofSeconds(20));
        assertThat(executionRepository.findByFlowId(MAIN_TENANT, NAMESPACE, FLOW_ID, Pageable.UNPAGED), hasSize(1));
    }
}
