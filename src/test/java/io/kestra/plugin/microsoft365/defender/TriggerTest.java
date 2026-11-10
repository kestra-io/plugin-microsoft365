package io.kestra.plugin.microsoft365.defender;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.StatefulTriggerInterface;
import io.kestra.core.models.triggers.StatefulTriggerService;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.utils.TestsUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;

@KestraTest
class TriggerTest extends AbstractDefenderTest {

    // Trigger ids are fixed per test, so the state left by a previous run is removed first
    private Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> mock(Trigger trigger) throws Exception {
        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = TestsUtils.mockTrigger(runContextFactory, trigger);
        StatefulTriggerService.writeState(runContext(context), key(context), new HashMap<>(), Optional.empty());
        return context;
    }

    private static RunContext runContext(Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context) {
        return context.getKey().getRunContext();
    }

    // mockTrigger reuses the same flow for every test, so each test uses its own trigger id and therefore its own state key
    private static String key(Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context) {
        return StatefulTriggerService.defaultKey(context.getValue().getNamespace(), context.getValue().getFlowId(), context.getValue().getTriggerId());
    }

    private static Map<String, StatefulTriggerService.Entry> state(Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context) {
        return StatefulTriggerService.readState(runContext(context), key(context), Optional.empty());
    }

    private static void seedWatermark(Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context, String watermark) {
        Map<String, StatefulTriggerService.Entry> state = new HashMap<>();
        Instant instant = Instant.parse(watermark);
        state.put(Trigger.WATERMARK_KEY, new StatefulTriggerService.Entry(Trigger.WATERMARK_KEY, instant.toString(), instant, Instant.now()));
        StatefulTriggerService.writeState(runContext(context), key(context), state, Optional.empty());
    }

    private static String watermark(Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context) {
        return state(context).get(Trigger.WATERMARK_KEY).version();
    }

    private static List<Object> storedItems(Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context, Execution execution) throws Exception {
        URI uri = URI.create(execution.getTrigger().getVariables().get("uri").toString());
        try (InputStream inputStream = runContext(context).storage().getFile(uri)) {
            return FileSerde.readAll(inputStream).collectList().block();
        }
    }

    private static void stubAlerts(String filter, String... alerts) {
        wm.resetAll();
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .withQueryParam("$filter", equalTo(filter))
            .willReturn(okJson("{\"value\":[" + String.join(",", alerts) + "]}")));
    }

    @Test
    void firstPollStoresWatermarkWithoutFiring() throws Exception {
        Trigger trigger = TestableTrigger.builder().id("first_poll").type(Trigger.class.getName()).build();
        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = mock(trigger);
        Instant before = Instant.now();

        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(false));
        assertThat(Instant.parse(watermark(context)), greaterThanOrEqualTo(before.minusSeconds(1)));
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void firesForAlertsSinceWatermarkMinusLookbackAndStoresThem() throws Exception {
        Trigger trigger = TestableTrigger.builder()
            .id("fires_since_watermark")
            .type(Trigger.class.getName())
            .severity(Property.ofValue(Severity.HIGH))
            .build();
        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = mock(trigger);
        seedWatermark(context, "2026-10-24T08:00:00Z");

        String nextLink = wm.baseUrl() + GRAPH + "/security/alerts_v2?$skiptoken=p2";
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .withQueryParam("$skiptoken", equalTo("p2"))
            .atPriority(1)
            .willReturn(okJson("{\"value\":[" + alertJson("a2", "high", "new", "2026-10-24T09:00:00Z") + "]}")));
        // default lookback of 5 minutes before the watermark, with ge so items sharing the watermark timestamp are read again
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .withQueryParam("$filter", equalTo("(lastUpdateDateTime ge 2026-10-24T07:55:00.0000000Z) and (severity eq 'high')"))
            .atPriority(2)
            .willReturn(okJson("{\"value\":[" + alertJson("a1", "high", "new", "2026-10-24T10:30:00.1234567Z") + "],"
                + "\"@odata.nextLink\":\"" + nextLink + "\"}")));

        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(true));
        Map<String, Object> variables = execution.get().getTrigger().getVariables();
        assertThat(variables.get("count"), is(2));
        assertThat(variables, not(hasKey("items")));
        List<Object> items = storedItems(context, execution.get());
        assertThat(items.stream().map(item -> ((Map<?, ?>) item).get("id")).toList(), contains("a1", "a2"));
        // the max lastUpdateDateTime across all pages, not the first item of the last page
        assertThat(watermark(context), is("2026-10-24T10:30:00.123456700Z"));
    }

    @Test
    void sameTimestampAndLateIndexedAlertsFireOnceEach() throws Exception {
        Trigger trigger = TestableTrigger.builder().id("same_timestamp").type(Trigger.class.getName()).build();
        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = mock(trigger);
        seedWatermark(context, "2026-10-24T08:00:00Z");

        stubAlerts("lastUpdateDateTime ge 2026-10-24T07:55:00.0000000Z", alertJson("a1", "high", "new", "2026-10-24T09:00:00Z"));
        Optional<Execution> first = trigger.evaluate(context.getKey(), context.getValue());
        assertThat(first.isPresent(), is(true));
        assertThat(first.get().getTrigger().getVariables().get("count"), is(1));

        // a2 has the same timestamp as a1 and a3 was indexed late with an older one: a strict gt on the watermark would miss both
        stubAlerts("lastUpdateDateTime ge 2026-10-24T08:55:00.0000000Z",
            alertJson("a1", "high", "new", "2026-10-24T09:00:00Z"),
            alertJson("a2", "high", "new", "2026-10-24T09:00:00Z"),
            alertJson("a3", "high", "new", "2026-10-24T08:58:00Z"));
        Optional<Execution> second = trigger.evaluate(context.getKey(), context.getValue());
        assertThat(second.isPresent(), is(true));
        assertThat(storedItems(context, second.get()).stream().map(item -> ((Map<?, ?>) item).get("id")).toList(), contains("a2", "a3"));

        // nothing new: no execution, a1 to a3 are not fired twice
        Optional<Execution> third = trigger.evaluate(context.getKey(), context.getValue());
        assertThat(third.isPresent(), is(false));
        assertThat(watermark(context), is("2026-10-24T09:00:00Z"));
    }

    @Test
    void onCreateIgnoresUpdatesOfSeenAlerts() throws Exception {
        Trigger trigger = TestableTrigger.builder()
            .id("on_create")
            .type(Trigger.class.getName())
            .on(Property.ofValue(StatefulTriggerInterface.On.CREATE))
            .build();
        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = mock(trigger);
        seedWatermark(context, "2026-10-24T08:00:00Z");

        stubAlerts("lastUpdateDateTime ge 2026-10-24T07:55:00.0000000Z", alertJson("a1", "high", "new", "2026-10-24T09:00:00Z"));
        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(true));

        stubAlerts("lastUpdateDateTime ge 2026-10-24T08:55:00.0000000Z", alertJson("a1", "high", "resolved", "2026-10-24T09:30:00Z"));
        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));
        assertThat(watermark(context), is("2026-10-24T09:30:00Z"));
    }

    @Test
    void pollsIncidentsAndDoesNotFireWhenNothingChanged() throws Exception {
        Trigger trigger = TestableTrigger.builder()
            .id("incidents_unchanged")
            .type(Trigger.class.getName())
            .resource(Property.ofValue(Trigger.Resource.INCIDENTS))
            .build();
        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = mock(trigger);
        seedWatermark(context, "2026-10-24T08:00:00Z");
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/incidents"))
            .withQueryParam("$filter", equalTo("lastUpdateDateTime ge 2026-10-24T07:55:00.0000000Z"))
            .willReturn(okJson("{\"value\":[]}")));

        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(false));
        assertThat(watermark(context), is("2026-10-24T08:00:00Z"));
        wm.verify(1, getRequestedFor(urlPathEqualTo(GRAPH + "/security/incidents")));
    }

    @Test
    void throttlingSkipsThePollAndKeepsTheState() throws Exception {
        Trigger trigger = TestableTrigger.builder().id("throttling").type(Trigger.class.getName()).build();
        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = mock(trigger);
        seedWatermark(context, "2026-10-24T08:00:00Z");

        String nextLink = wm.baseUrl() + GRAPH + "/security/alerts_v2?$skiptoken=p2";
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .withQueryParam("$skiptoken", equalTo("p2"))
            .atPriority(1)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")));
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .atPriority(2)
            .willReturn(okJson("{\"value\":[" + alertJson("a1", "high", "new", "2026-10-24T10:00:00Z") + "],"
                + "\"@odata.nextLink\":\"" + nextLink + "\"}")));

        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(false));
        assertThat(watermark(context), is("2026-10-24T08:00:00Z"));
        // a1 was read on the first page but not recorded, so the next poll fires it
        assertThat(state(context), not(hasKey("a1")));
    }

    @Test
    void executionCreationFailureKeepsStateSoNextPollRetries() throws Exception {
        Trigger trigger = TestableTrigger.builder().id("execution_failure").type(Trigger.class.getName()).build();
        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = mock(trigger);
        seedWatermark(context, "2026-10-24T08:00:00Z");
        stubAlerts("lastUpdateDateTime ge 2026-10-24T07:55:00.0000000Z",
            alertJson("a1", "high", "new", "2026-10-24T09:00:00Z"));

        try (MockedStatic<TriggerService> service = mockStatic(TriggerService.class)) {
            service.when(() -> TriggerService.generateExecution(
                org.mockito.ArgumentMatchers.eq(trigger),
                org.mockito.ArgumentMatchers.eq(context.getKey()),
                org.mockito.ArgumentMatchers.eq(context.getValue()),
                org.mockito.ArgumentMatchers.any(Trigger.Output.class)
            )).thenThrow(new IllegalStateException("Execution creation failed"));

            assertThrows(IllegalStateException.class, () -> trigger.evaluate(context.getKey(), context.getValue()));
        }

        assertThat(watermark(context), is("2026-10-24T08:00:00Z"));
        assertThat(state(context), not(hasKey("a1")));
        Optional<Execution> retry = trigger.evaluate(context.getKey(), context.getValue());
        assertThat(retry.isPresent(), is(true));
        assertThat(storedItems(context, retry.get()).stream().map(item -> ((Map<?, ?>) item).get("id")).toList(), contains("a1"));
    }

    @Test
    void lookbackDoesNotReplayItemsFromBeforeTheFirstPoll() throws Exception {
        Trigger trigger = TestableTrigger.builder().id("initial_lookback").type(Trigger.class.getName()).build();
        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = mock(trigger);
        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));
        Instant start = Instant.parse(watermark(context));
        String filter = "lastUpdateDateTime ge " + DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'")
            .withZone(ZoneOffset.UTC).format(start);
        stubAlerts(filter);

        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));

        wm.verify(1, getRequestedFor(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .withQueryParam("$filter", equalTo(filter)));
    }

    @Test
    void largeBacklogIsStoredWithoutEmbeddingItemsInTheExecution() throws Exception {
        Trigger trigger = TestableTrigger.builder().id("large_backlog").type(Trigger.class.getName()).build();
        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = mock(trigger);
        seedWatermark(context, "2026-10-24T08:00:00Z");
        String[] alerts = new String[2000];
        for (int i = 0; i < alerts.length; i++) {
            alerts[i] = alertJson("alert_" + i, "high", "new", "2026-10-24T09:00:00Z");
        }
        stubAlerts("lastUpdateDateTime ge 2026-10-24T07:55:00.0000000Z", alerts);

        Execution execution = trigger.evaluate(context.getKey(), context.getValue())
            .orElseThrow(() -> new AssertionError("Expected a backlog execution"));

        assertThat(execution.getTrigger().getVariables().get("count"), is(alerts.length));
        assertThat(execution.getTrigger().getVariables(), not(hasKey("items")));
        assertThat(storedItems(context, execution), hasSize(alerts.length));
    }
}
