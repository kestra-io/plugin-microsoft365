package io.kestra.plugin.microsoft365.defender;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class AlertsTest extends AbstractDefenderTest {

    @Test
    void listAlertsCombinesFiltersAndStopsAtTop() throws Exception {
        String nextLink = wm.baseUrl() + GRAPH + "/security/alerts_v2?$top=3&$skiptoken=page2";
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .withQueryParam("$skiptoken", equalTo("page2"))
            .atPriority(1)
            .willReturn(okJson("{\"value\":[" + alertJson("a3", "high", "new", "2026-10-24T08:00:00Z") + ","
                + alertJson("a4", "high", "new", "2026-10-24T07:00:00Z") + "]}")));
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .withQueryParam("$top", equalTo("3"))
            .withQueryParam("$filter", equalTo("(serviceSource eq 'microsoftDefenderForEndpoint') and (severity eq 'high') and (status eq 'new')"))
            .atPriority(2)
            .willReturn(okJson("{\"value\":[" + alertJson("a1", "high", "new", "2026-10-24T10:00:00Z") + ","
                + alertJson("a2", "high", "new", "2026-10-24T09:00:00Z") + "],\"@odata.nextLink\":\"" + nextLink + "\"}")));

        ListAlerts task = withWireMock(ListAlerts.builder()
            .filter(Property.ofValue("serviceSource eq 'microsoftDefenderForEndpoint'"))
            .severity(Property.ofValue("high"))
            .status(Property.ofValue("new"))
            .top(Property.ofValue(3))
            .build());

        ListAlerts.Output output = task.run(runContext());

        assertThat(output.getCount(), is(3));
        assertThat(output.getAlerts().stream().map(a -> a.get("id")).toList(), contains("a1", "a2", "a3"));
        Map<String, Object> first = output.getAlerts().getFirst();
        assertThat(first.get("severity"), is("high"));
        assertThat(first.get("serviceSource"), is("microsoftDefenderForEndpoint"));
        assertThat(first.get("lastUpdateDateTime").toString(), startsWith("2026-10-24T10:00"));
        assertThat(first, not(hasKey("@odata.type")));
        assertThat((List<?>) first.get("evidence"), hasSize(1));
        wm.verify(2, getRequestedFor(urlPathEqualTo(GRAPH + "/security/alerts_v2")));
    }

    @Test
    void listAlertsStoreWritesIonFile() throws Exception {
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .willReturn(okJson("{\"value\":[" + alertJson("a1", "low", "new", "2026-10-24T10:00:00Z") + "]}")));

        ListAlerts task = withWireMock(ListAlerts.builder().fetchType(Property.ofValue(FetchType.STORE)).build());
        RunContext runContext = runContext();

        ListAlerts.Output output = task.run(runContext);

        assertThat(output.getCount(), is(1));
        assertThat(output.getAlerts(), nullValue());
        assertThat(output.getUri(), notNullValue());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(runContext.storage().getFile(output.getUri())))) {
            assertThat(reader.readLine(), containsString("a1"));
        }
        wm.verify(getRequestedFor(urlPathEqualTo(GRAPH + "/security/alerts_v2")).withQueryParam("$top", equalTo("100")));
    }

    @Test
    void listAlertsFetchOne() throws Exception {
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .willReturn(okJson("{\"value\":[" + alertJson("a1", "low", "new", "2026-10-24T10:00:00Z") + ","
                + alertJson("a2", "low", "new", "2026-10-24T09:00:00Z") + "]}")));

        ListAlerts.Output output = withWireMock(ListAlerts.builder().fetchType(Property.ofValue(FetchType.FETCH_ONE)).build()).run(runContext());

        assertThat(output.getAlert().get("id"), is("a1"));
        assertThat(output.getAlerts(), nullValue());
    }

    @Test
    void listAlertsRejectsInvalidValues() throws Exception {
        var badSeverity = withWireMock(ListAlerts.builder().severity(Property.ofValue("critical")).build());
        var e = assertThrows(IllegalArgumentException.class, () -> badSeverity.run(runContext()));
        assertThat(e.getMessage(), containsString("informational, low, medium, high"));

        var badTop = withWireMock(ListAlerts.builder().top(Property.ofValue(2001)).build());
        assertThrows(IllegalArgumentException.class, () -> badTop.run(runContext()));
        wm.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void throttlingErrorMentionsRetryAfter() throws Exception {
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2"))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"TooManyRequests\",\"message\":\"Too many requests\"}}")));

        var task = withWireMock(ListAlerts.builder().build());
        var e = assertThrows(IllegalStateException.class, () -> task.run(runContext()));

        assertThat(e.getMessage(), containsString("HTTP 429"));
        assertThat(e.getMessage(), containsString("Retry-After: 1s"));
        assertThat(e.getMessage(), containsString("TooManyRequests"));
    }

    @Test
    void getAlert() throws Exception {
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/alerts_v2/da123"))
            .willReturn(okJson(alertJson("da123", "medium", "inProgress", "2026-10-24T10:00:00Z"))));

        GetAlert.Output output = withWireMock(GetAlert.builder().alertId(Property.ofValue("da123")).build()).run(runContext());

        assertThat(output.getAlert().get("id"), is("da123"));
        assertThat(output.getAlert().get("status"), is("inProgress"));
        assertThat(output.getAlert().get("title"), is("Suspicious PowerShell"));
    }

    @Test
    void updateAlertSendsOnlySetProperties() throws Exception {
        wm.stubFor(patch(urlPathEqualTo(GRAPH + "/security/alerts_v2/da123"))
            .willReturn(okJson(alertJson("da123", "medium", "resolved", "2026-10-24T11:00:00Z"))));

        UpdateAlert task = withWireMock(UpdateAlert.builder()
            .alertId(Property.ofValue("da123"))
            .status(Property.ofValue("resolved"))
            .classification(Property.ofValue("falsePositive"))
            .build());

        UpdateAlert.Output output = task.run(runContext());

        assertThat(output.getAlert().get("status"), is("resolved"));
        wm.verify(patchRequestedFor(urlPathEqualTo(GRAPH + "/security/alerts_v2/da123"))
            .withRequestBody(matchingJsonPath("$.status", equalTo("resolved")))
            .withRequestBody(matchingJsonPath("$.classification", equalTo("falsePositive")))
            .withRequestBody(notContaining("determination"))
            .withRequestBody(notContaining("assignedTo")));
    }

    @Test
    void updateAlertRequiresAProperty() throws Exception {
        var task = withWireMock(UpdateAlert.builder().alertId(Property.ofValue("da123")).build());
        assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));

        var badStatus = withWireMock(UpdateAlert.builder().alertId(Property.ofValue("da123")).status(Property.ofValue("closed")).build());
        var e = assertThrows(IllegalArgumentException.class, () -> badStatus.run(runContext()));
        assertThat(e.getMessage(), containsString("new, inProgress, resolved"));
        wm.verify(0, anyRequestedFor(anyUrl()));
    }
}
