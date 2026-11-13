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
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

@KestraTest
class TriggerTest extends AbstractDefenderTest {

    @Test
    void rejectsInvalidDurationsBeforeReadingStateOrCallingGraph() throws Exception {
        Duration[][] invalid = {
            {Duration.ofSeconds(-1), Duration.ofDays(7)},
            {Duration.ZERO, Duration.ZERO},
            {Duration.ZERO, Duration.ofSeconds(-1)},
            {Duration.ofMinutes(5), Duration.ofMinutes(5)},
            {Duration.ofMinutes(5), Duration.ofMinutes(4)}
        };
        for (var durations : invalid) {
            var trigger = TestableTrigger.builder().id(IdUtils.create()).type(Trigger.class.getName())
                .lookback(Property.ofValue(durations[0]))
                .stateTtl(Property.ofValue(durations[1])).build();
            var context = mock(trigger);
            assertThrows(IllegalArgumentException.class, () -> trigger.evaluate(context.getKey(), context.getValue()));
            assertThat(state(context).isEmpty(), is(true));
        }
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void permitsZeroLookbackWithPositiveTtl() throws Exception {
        var trigger = TestableTrigger.builder().id(IdUtils.create()).type(Trigger.class.getName())
            .lookback(Property.ofValue(Duration.ZERO)).stateTtl(Property.ofValue(Duration.ofHours(1))).build();
        var context = mock(trigger);
        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));
        assertThat(state(context), hasKey(Trigger.WATERMARK_KEY));
    }

    @Test
    void expiredEntryInsideLookbackIsRefreshedWithoutFiringAgain() throws Exception {
        var trigger = TestableTrigger.builder().id(IdUtils.create()).type(Trigger.class.getName()).build();
        var context = mock(trigger);
        seedWatermark(context, "2026-10-24T09:00:00Z");
        var entries = state(context);
        var modifiedAt = Instant.parse("2026-10-24T08:58:00Z");
        var expired = Instant.now().minus(Duration.ofDays(8));
        entries.put("a1", new StatefulTriggerService.Entry("a1", modifiedAt.toString(), modifiedAt, expired));
        entries.put("outside_window", new StatefulTriggerService.Entry("outside_window", "old",
            Instant.parse("2026-10-24T08:00:00Z"), expired));
        StatefulTriggerService.writeState(runContext(context), key(context), entries, Optional.empty());
        stubAlerts("lastUpdateDateTime ge 2026-10-24T08:55:00.0000000Z",
            alertJson("a1", "high", "new", modifiedAt.toString()));

        var before = Instant.now();
        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));
        var refreshed = state(context);
        assertThat(refreshed.get("a1").lastSeenAt(), greaterThanOrEqualTo(before));
        assertThat(refreshed, not(hasKey("outside_window")));

        // Unchanged items must refresh their lastSeenAt on every poll: with an older lastSeenAt seeded, a missing refresh fails
        var stale = Instant.now().minus(Duration.ofHours(1));
        var a1 = refreshed.get("a1");
        refreshed.put("a1", new StatefulTriggerService.Entry("a1", a1.version(), a1.modifiedAt(), stale));
        StatefulTriggerService.writeState(runContext(context), key(context), refreshed, Optional.empty());
        var beforeSecondPoll = Instant.now();
        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));
        assertThat(state(context).get("a1").lastSeenAt(), greaterThanOrEqualTo(beforeSecondPoll));
    }

    @Test
    void invalidDurationMessagesGiveTheValuesAndTheFix() throws Exception {
        assertThat(invalidDurationMessage(Duration.ofSeconds(-1), Duration.ofDays(7)),
            is("lookback (PT-1S) must be zero or positive. Set lookback to PT0S or longer."));
        assertThat(invalidDurationMessage(Duration.ZERO, Duration.ZERO),
            is("stateTtl (PT0S) must be positive. Set stateTtl to a positive duration such as P7D."));
        assertThat(invalidDurationMessage(Duration.ofMinutes(5), Duration.ofMinutes(5)),
            is("stateTtl (PT5M) must be longer than lookback (PT5M). Increase stateTtl or reduce lookback."));
    }

    private String invalidDurationMessage(Duration lookback, Duration stateTtl) {
        var trigger = TestableTrigger.builder().id(IdUtils.create()).type(Trigger.class.getName())
            .lookback(Property.ofValue(lookback))
            .stateTtl(Property.ofValue(stateTtl)).build();
        var context = mock(trigger);
        return assertThrows(IllegalArgumentException.class, () -> trigger.evaluate(context.getKey(), context.getValue())).getMessage();
    }

    private Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> mock(Trigger trigger) {
        return TestsUtils.mockTrigger(runContextFactory, trigger);
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
        seedWatermark(context, Trigger.WATERMARK_KEY, watermark);
    }

    private static void seedWatermark(Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context, String watermarkKey, String watermark) {
        var state = new HashMap<String, StatefulTriggerService.Entry>();
        var instant = Instant.parse(watermark);
        state.put(watermarkKey, new StatefulTriggerService.Entry(watermarkKey, instant.toString(), instant, Instant.now()));
        StatefulTriggerService.writeState(runContext(context), key(context), state, Optional.empty());
    }

    private static String watermark(Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context) {
        return state(context).get(Trigger.WATERMARK_KEY).version();
    }

    private static List<Object> storedItems(Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context, Execution execution) throws Exception {
        var uri = URI.create(execution.getTrigger().getVariables().get("uri").toString());
        try (var inputStream = runContext(context).storage().getFile(uri)) {
            return FileSerde.readAll(inputStream).collectList().block();
        }
    }

    private static void stubAlerts(String filter, String... alerts) {
        wm.resetAll();
        wm.stubFor(get(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH)))
            .withQueryParam("$filter", equalTo(filter))
            .willReturn(okJson("{\"value\":[%s]}".formatted(String.join(",", alerts)))));
    }

    @Test
    void firstPollStoresWatermarkWithoutFiring() throws Exception {
        var trigger = TestableTrigger.builder().id(IdUtils.create()).type(Trigger.class.getName()).build();
        var context = mock(trigger);
        var before = Instant.now();

        var execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(false));
        assertThat(Instant.parse(watermark(context)), greaterThanOrEqualTo(before.minusSeconds(1)));
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void firesForAlertsSinceWatermarkMinusLookbackAndStoresThem() throws Exception {
        var trigger = TestableTrigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .severity(Property.ofValue(Severity.HIGH))
            .build();
        var context = mock(trigger);
        seedWatermark(context, "2026-10-24T08:00:00Z");

        var nextLink = "%s%s/security/alerts_v2?$skiptoken=p2".formatted(wm.baseUrl(), GRAPH);
        wm.stubFor(get(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH)))
            .withQueryParam("$skiptoken", equalTo("p2"))
            .atPriority(1)
            .willReturn(okJson("{\"value\":[%s]}".formatted(alertJson("a2", "high", "new", "2026-10-24T09:00:00Z")))));
        // default lookback of 5 minutes before the watermark, with ge so items sharing the watermark timestamp are read again
        wm.stubFor(get(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH)))
            .withQueryParam("$filter", equalTo("(lastUpdateDateTime ge 2026-10-24T07:55:00.0000000Z) and (severity eq 'high')"))
            .atPriority(2)
            .willReturn(okJson("{\"value\":[%s],\"@odata.nextLink\":\"%s\"}".formatted(
                alertJson("a1", "high", "new", "2026-10-24T10:30:00.1234567Z"), nextLink))));

        var execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(true));
        var variables = execution.get().getTrigger().getVariables();
        assertThat(variables.get("count"), is(2));
        assertThat(variables, not(hasKey("items")));
        var items = storedItems(context, execution.get());
        assertThat(items.stream().map(item -> ((Map<?, ?>) item).get("id")).toList(), contains("a1", "a2"));
        // the max lastUpdateDateTime across all pages, not the first item of the last page
        assertThat(watermark(context), is("2026-10-24T10:30:00.123456700Z"));
    }

    @Test
    void sameTimestampAndLateIndexedAlertsFireOnceEach() throws Exception {
        var trigger = TestableTrigger.builder().id(IdUtils.create()).type(Trigger.class.getName()).build();
        var context = mock(trigger);
        seedWatermark(context, "2026-10-24T08:00:00Z");

        stubAlerts("lastUpdateDateTime ge 2026-10-24T07:55:00.0000000Z", alertJson("a1", "high", "new", "2026-10-24T09:00:00Z"));
        var first = trigger.evaluate(context.getKey(), context.getValue());
        assertThat(first.isPresent(), is(true));
        assertThat(first.get().getTrigger().getVariables().get("count"), is(1));

        // a2 has the same timestamp as a1 and a3 was indexed late with an older one: a strict gt on the watermark would miss both
        stubAlerts("lastUpdateDateTime ge 2026-10-24T08:55:00.0000000Z",
            alertJson("a1", "high", "new", "2026-10-24T09:00:00Z"),
            alertJson("a2", "high", "new", "2026-10-24T09:00:00Z"),
            alertJson("a3", "high", "new", "2026-10-24T08:58:00Z"));
        var second = trigger.evaluate(context.getKey(), context.getValue());
        assertThat(second.isPresent(), is(true));
        assertThat(storedItems(context, second.get()).stream().map(item -> ((Map<?, ?>) item).get("id")).toList(), contains("a2", "a3"));

        // nothing new: no execution, a1 to a3 are not fired twice
        var third = trigger.evaluate(context.getKey(), context.getValue());
        assertThat(third.isPresent(), is(false));
        assertThat(watermark(context), is("2026-10-24T09:00:00Z"));
    }

    @Test
    void onCreateIgnoresUpdatesOfSeenAlerts() throws Exception {
        var trigger = TestableTrigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .on(Property.ofValue(StatefulTriggerInterface.On.CREATE))
            .build();
        var context = mock(trigger);
        seedWatermark(context, Trigger.CREATED_WATERMARK_KEY, "2026-10-24T08:00:00Z");

        stubAlerts("createdDateTime ge 2026-10-24T07:55:00.0000000Z", alertJson("a1", "high", "new", "2026-10-24T09:00:00Z"));
        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(true));

        stubAlerts("createdDateTime ge 2026-10-24T08:55:00.0000000Z",
            alertJson("a1", "high", "resolved", "2026-10-24T09:00:00Z", "2026-10-24T09:30:00Z"));
        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));
        assertThat(state(context).get(Trigger.CREATED_WATERMARK_KEY).version(), is("2026-10-24T09:00:00Z"));
    }

    @Test
    void switchingOnModeRestartsFromANewStartingPoint() throws Exception {
        var trigger = TestableTrigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .on(Property.ofValue(StatefulTriggerInterface.On.CREATE))
            .build();
        var context = mock(trigger);
        seedWatermark(context, "2026-10-24T08:00:00Z");

        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));

        wm.verify(0, anyRequestedFor(anyUrl()));
        assertThat(state(context), hasKey(Trigger.CREATED_WATERMARK_KEY));
        assertThat(state(context), not(hasKey(Trigger.WATERMARK_KEY)));
    }

    @Test
    void onCreateDoesNotFireAgainForAForgottenAlertThatChanges() throws Exception {
        var trigger = TestableTrigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .on(Property.ofValue(StatefulTriggerInterface.On.CREATE))
            .build();
        var context = mock(trigger);
        // a1 was fired long ago and forgotten after stateTtl. Both watermarks are seeded so the old lastUpdateDateTime polling would run too.
        var watermark = Instant.parse("2026-10-24T09:00:00Z");
        var created = Instant.parse("2026-10-10T09:00:00Z");
        var expired = Instant.now().minus(Duration.ofDays(8));
        var state = new HashMap<String, StatefulTriggerService.Entry>();
        state.put(Trigger.WATERMARK_KEY, new StatefulTriggerService.Entry(Trigger.WATERMARK_KEY, watermark.toString(), watermark, Instant.now()));
        state.put(Trigger.CREATED_WATERMARK_KEY, new StatefulTriggerService.Entry(Trigger.CREATED_WATERMARK_KEY, watermark.toString(), watermark, Instant.now()));
        state.put("a1", new StatefulTriggerService.Entry("a1", created.toString(), created, expired));
        StatefulTriggerService.writeState(runContext(context), key(context), state, Optional.empty());

        // a1 changes again at 09:30: only a lastUpdateDateTime query returns it
        var a1 = alertJson("a1", "high", "resolved", "2026-10-10T09:00:00Z", "2026-10-24T09:30:00Z");
        var a2 = alertJson("a2", "high", "new", "2026-10-24T09:01:00Z", "2026-10-24T09:01:00Z");
        wm.stubFor(get(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH)))
            .withQueryParam("$filter", equalTo("createdDateTime ge 2026-10-24T08:55:00.0000000Z"))
            .willReturn(okJson("{\"value\":[%s]}".formatted(a2))));
        wm.stubFor(get(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH)))
            .withQueryParam("$filter", equalTo("lastUpdateDateTime ge 2026-10-24T08:55:00.0000000Z"))
            .willReturn(okJson("{\"value\":[%s,%s]}".formatted(a1, a2))));

        var execution = trigger.evaluate(context.getKey(), context.getValue())
            .orElseThrow(() -> new AssertionError("Expected an execution for a2"));

        assertThat(storedItems(context, execution).stream().map(item -> ((Map<?, ?>) item).get("id")).toList(), contains("a2"));
        assertThat(state(context), not(hasKey("a1")));
        assertThat(state(context).get(Trigger.CREATED_WATERMARK_KEY).version(), is("2026-10-24T09:01:00Z"));
    }

    @Test
    void pollsIncidentsAndDoesNotFireWhenNothingChanged() throws Exception {
        var trigger = TestableTrigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .resource(Property.ofValue(Trigger.Resource.INCIDENTS))
            .build();
        var context = mock(trigger);
        seedWatermark(context, "2026-10-24T08:00:00Z");
        wm.stubFor(get(urlPathEqualTo("%s/security/incidents".formatted(GRAPH)))
            .withQueryParam("$filter", equalTo("lastUpdateDateTime ge 2026-10-24T07:55:00.0000000Z"))
            .willReturn(okJson("{\"value\":[]}")));

        var execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(false));
        assertThat(watermark(context), is("2026-10-24T08:00:00Z"));
        wm.verify(1, getRequestedFor(urlPathEqualTo("%s/security/incidents".formatted(GRAPH))));
    }

    @Test
    void throttlingSkipsThePollAndKeepsTheState() throws Exception {
        var trigger = TestableTrigger.builder().id(IdUtils.create()).type(Trigger.class.getName()).build();
        var context = mock(trigger);
        seedWatermark(context, "2026-10-24T08:00:00Z");

        var nextLink = "%s%s/security/alerts_v2?$skiptoken=p2".formatted(wm.baseUrl(), GRAPH);
        wm.stubFor(get(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH)))
            .withQueryParam("$skiptoken", equalTo("p2"))
            .atPriority(1)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")));
        wm.stubFor(get(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH)))
            .atPriority(2)
            .willReturn(okJson("{\"value\":[%s],\"@odata.nextLink\":\"%s\"}".formatted(
                alertJson("a1", "high", "new", "2026-10-24T10:00:00Z"), nextLink))));

        var execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(false));
        assertThat(watermark(context), is("2026-10-24T08:00:00Z"));
        // a1 was read on the first page but not recorded, so the next poll fires it
        assertThat(state(context), not(hasKey("a1")));
    }

    @Test
    void executionCreationFailureKeepsStateSoNextPollRetries() throws Exception {
        var trigger = TestableTrigger.builder().id(IdUtils.create()).type(Trigger.class.getName()).build();
        var context = mock(trigger);
        seedWatermark(context, "2026-10-24T08:00:00Z");
        stubAlerts("lastUpdateDateTime ge 2026-10-24T07:55:00.0000000Z",
            alertJson("a1", "high", "new", "2026-10-24T09:00:00Z"));

        try (var service = mockStatic(TriggerService.class)) {
            service.when(() -> TriggerService.generateExecution(
                eq(trigger),
                org.mockito.ArgumentMatchers.eq(context.getKey()),
                org.mockito.ArgumentMatchers.eq(context.getValue()),
                org.mockito.ArgumentMatchers.any(Trigger.Output.class)
            )).thenThrow(new IllegalStateException("Execution creation failed"));

            assertThrows(IllegalStateException.class, () -> trigger.evaluate(context.getKey(), context.getValue()));
        }

        assertThat(watermark(context), is("2026-10-24T08:00:00Z"));
        assertThat(state(context), not(hasKey("a1")));
        var retry = trigger.evaluate(context.getKey(), context.getValue());
        assertThat(retry.isPresent(), is(true));
        assertThat(storedItems(context, retry.get()).stream().map(item -> ((Map<?, ?>) item).get("id")).toList(), contains("a1"));
    }

    @Test
    void lookbackDoesNotReplayItemsFromBeforeTheFirstPoll() throws Exception {
        var trigger = TestableTrigger.builder().id(IdUtils.create()).type(Trigger.class.getName()).build();
        var context = mock(trigger);
        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));
        var start = Instant.parse(watermark(context));
        var filter = "lastUpdateDateTime ge %s".formatted(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'")
            .withZone(ZoneOffset.UTC).format(start));
        stubAlerts(filter);

        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));

        wm.verify(1, getRequestedFor(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH)))
            .withQueryParam("$filter", equalTo(filter)));
    }

    @Test
    void largeBacklogIsStoredWithoutEmbeddingItemsInTheExecution() throws Exception {
        var trigger = TestableTrigger.builder().id(IdUtils.create()).type(Trigger.class.getName()).build();
        var context = mock(trigger);
        seedWatermark(context, "2026-10-24T08:00:00Z");
        var alerts = new String[2000];
        for (var i = 0; i < alerts.length; i++) {
            alerts[i] = alertJson("alert_%d".formatted(i), "high", "new", "2026-10-24T09:00:00Z");
        }
        stubAlerts("lastUpdateDateTime ge 2026-10-24T07:55:00.0000000Z", alerts);

        var execution = trigger.evaluate(context.getKey(), context.getValue())
            .orElseThrow(() -> new AssertionError("Expected a backlog execution"));

        assertThat(execution.getTrigger().getVariables().get("count"), is(alerts.length));
        assertThat(execution.getTrigger().getVariables(), not(hasKey("items")));
        assertThat(storedItems(context, execution), hasSize(alerts.length));
    }
}
