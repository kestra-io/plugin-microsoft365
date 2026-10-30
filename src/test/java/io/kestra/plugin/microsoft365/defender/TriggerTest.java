package io.kestra.plugin.microsoft365.defender;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValue;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class TriggerTest extends AbstractDefenderTest {

    // mockTrigger reuses the same flow for every test, so each test uses its own trigger id (and KV watermark key)
    private static String triggerId() {
        return "defender_" + IdUtils.create();
    }

    private Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> mock(Trigger trigger) {
        return TestsUtils.mockTrigger(runContextFactory, trigger);
    }

    private static KVStore kv(Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context) {
        return context.getKey().getRunContext().namespaceKv(context.getValue().getNamespace());
    }

    private static String key(Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context) {
        return Trigger.watermarkKey(context.getValue().getFlowId(), context.getValue().getTriggerId());
    }

    @Test
    void firstPollStoresWatermarkWithoutFiring() throws Exception {
        Trigger trigger = TestableTrigger.builder().id(triggerId()).type(Trigger.class.getName()).build();
        var context = mock(trigger);
        Instant before = Instant.now();

        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(false));
        Optional<KVValue> stored = kv(context).getValue(key(context));
        assertThat(stored.isPresent(), is(true));
        assertThat(Instant.parse(stored.get().value().toString()), greaterThanOrEqualTo(before.minusSeconds(1)));
        assertThat(key(context), is("defender_watermark_" + context.getValue().getFlowId().length() + "_"
            + context.getValue().getFlowId() + "_" + trigger.getId()));
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void firesForAlertsNewerThanWatermarkAndAdvancesIt() throws Exception {
        Trigger trigger = TestableTrigger.builder()
            .id(triggerId())
            .type(Trigger.class.getName())
            .severity(Property.ofValue("high"))
            .build();
        var context = mock(trigger);
        kv(context).put(key(context), new KVValueAndMetadata(new KVMetadata(null, (Duration) null), "2026-10-24T08:00:00Z"));

        String nextLink = wm.baseUrl() + GRAPH + "/security/alerts_v2?$skiptoken=p2";
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .withQueryParam("$skiptoken", equalTo("p2"))
            .atPriority(1)
            .willReturn(okJson("{\"value\":[" + alertJson("a2", "high", "new", "2026-10-24T09:00:00Z") + "]}")));
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .withQueryParam("$filter", equalTo("(lastUpdateDateTime gt 2026-10-24T08:00:00.0000000Z) and (severity eq 'high')"))
            .atPriority(2)
            .willReturn(okJson("{\"value\":[" + alertJson("a1", "high", "new", "2026-10-24T10:30:00.1234567Z") + "],"
                + "\"@odata.nextLink\":\"" + nextLink + "\"}")));

        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(true));
        Map<String, Object> outputs = execution.get().getTrigger().getVariables();
        assertThat(outputs.get("count"), is(2));
        assertThat(((List<?>) outputs.get("items")), hasSize(2));
        // the max lastUpdateDateTime across all pages, not the first item of the last page
        assertThat(kv(context).getValue(key(context)).orElseThrow().value(), is("2026-10-24T10:30:00.123456700Z"));
    }

    @Test
    void pollsIncidentsAndDoesNotFireWhenNothingChanged() throws Exception {
        Trigger trigger = TestableTrigger.builder()
            .id(triggerId())
            .type(Trigger.class.getName())
            .resource(Property.ofValue(Trigger.Resource.INCIDENTS))
            .build();
        var context = mock(trigger);
        kv(context).put(key(context), new KVValueAndMetadata(new KVMetadata(null, (Duration) null), "2026-10-24T08:00:00Z"));
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/incidents"))
            .withQueryParam("$filter", equalTo("lastUpdateDateTime gt 2026-10-24T08:00:00.0000000Z"))
            .willReturn(okJson("{\"value\":[]}")));

        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(false));
        assertThat(kv(context).getValue(key(context)).orElseThrow().value(), is("2026-10-24T08:00:00Z"));
        wm.verify(1, getRequestedFor(urlPathEqualTo(GRAPH + "/security/incidents")));
    }

    @Test
    void throttlingSkipsThePollAndKeepsTheWatermark() throws Exception {
        Trigger trigger = TestableTrigger.builder().id(triggerId()).type(Trigger.class.getName()).build();
        var context = mock(trigger);
        kv(context).put(key(context), new KVValueAndMetadata(new KVMetadata(null, (Duration) null), "2026-10-24T08:00:00Z"));

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
        assertThat(kv(context).getValue(key(context)).orElseThrow().value(), is("2026-10-24T08:00:00Z"));
    }
}
