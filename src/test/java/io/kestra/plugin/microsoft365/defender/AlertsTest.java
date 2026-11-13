package io.kestra.plugin.microsoft365.defender;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.utils.IdUtils;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class AlertsTest extends AbstractDefenderTest {

    @Test
    void listAlertsCombinesFiltersAndStopsAtTop() throws Exception {
        var nextLink = "%s%s/security/alerts_v2?$top=3&$skiptoken=page2".formatted(wm.baseUrl(), GRAPH);
        wm.stubFor(get(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH)))
            .withQueryParam("$skiptoken", equalTo("page2"))
            .atPriority(1)
            .willReturn(okJson("{\"value\":[%s,%s]}".formatted(
                alertJson("a3", "high", "new", "2026-10-24T08:00:00Z"), alertJson("a4", "high", "new", "2026-10-24T07:00:00Z")))));
        wm.stubFor(get(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH)))
            .withQueryParam("$top", equalTo("3"))
            .withQueryParam("$filter", equalTo("(serviceSource eq 'microsoftDefenderForEndpoint') and (severity eq 'high') and (status eq 'new')"))
            .atPriority(2)
            .willReturn(okJson("{\"value\":[%s,%s],\"@odata.nextLink\":\"%s\"}".formatted(
                alertJson("a1", "high", "new", "2026-10-24T10:00:00Z"), alertJson("a2", "high", "new", "2026-10-24T09:00:00Z"), nextLink))));

        var task = withWireMock(ListAlerts.builder().id(IdUtils.create()).type(ListAlerts.class.getName())
            .filter(Property.ofValue("serviceSource eq 'microsoftDefenderForEndpoint'"))
            .severity(Property.ofValue(Severity.HIGH))
            .status(Property.ofValue(AlertStatus.NEW))
            .top(Property.ofValue(3))
            .build());

        var output = task.run(runContext());

        assertThat(output.getCount(), is(3));
        assertThat(output.getAlerts().stream().map(a -> a.get("id")).toList(), contains("a1", "a2", "a3"));
        var first = output.getAlerts().getFirst();
        assertThat(first.get("severity"), is("high"));
        assertThat(first.get("serviceSource"), is("microsoftDefenderForEndpoint"));
        assertThat(first.get("lastUpdateDateTime").toString(), startsWith("2026-10-24T10:00"));
        assertThat(first, not(hasKey("@odata.type")));
        assertThat((List<?>) first.get("evidence"), hasSize(1));
        wm.verify(2, getRequestedFor(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH))));
    }

    @Test
    void listAlertsStoreWritesIonFile() throws Exception {
        wm.stubFor(get(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH)))
            .willReturn(okJson("{\"value\":[%s]}".formatted(alertJson("a1", "low", "new", "2026-10-24T10:00:00Z")))));

        var task = withWireMock(ListAlerts.builder()
            .id(IdUtils.create())
            .type(ListAlerts.class.getName())
            .fetchType(Property.ofValue(FetchType.STORE))
            .build());
        var runContext = runContext();

        var output = task.run(runContext);

        assertThat(output.getCount(), is(1));
        assertThat(output.getAlerts(), nullValue());
        assertThat(output.getUri(), notNullValue());
        try (var reader = new BufferedReader(new InputStreamReader(runContext.storage().getFile(output.getUri())))) {
            assertThat(reader.readLine(), containsString("a1"));
        }
        wm.verify(getRequestedFor(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH))).withQueryParam("$top", equalTo("100")));
    }

    @Test
    void listAlertsFetchOne() throws Exception {
        wm.stubFor(get(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH)))
            .willReturn(okJson("{\"value\":[%s,%s]}".formatted(
                alertJson("a1", "low", "new", "2026-10-24T10:00:00Z"), alertJson("a2", "low", "new", "2026-10-24T09:00:00Z")))));

        var output = withWireMock(ListAlerts.builder()
            .id(IdUtils.create())
            .type(ListAlerts.class.getName())
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build()).run(runContext());

        assertThat(output.getAlert().get("id"), is("a1"));
        assertThat(output.getAlerts(), nullValue());
    }

    @Test
    void listAlertsRejectsInvalidValues() throws Exception {
        // A rendered value outside the enum fails before any call to Graph
        var badSeverity = withWireMock(ListAlerts.builder()
            .id(IdUtils.create())
            .type(ListAlerts.class.getName())
            .severity(Property.ofExpression("{{ 'CRITICAL' }}"))
            .build());
        assertThrows(IllegalVariableEvaluationException.class, () -> badSeverity.run(runContext()));

        var badTop = withWireMock(ListAlerts.builder()
            .id(IdUtils.create())
            .type(ListAlerts.class.getName())
            .top(Property.ofValue(2001))
            .build());
        assertThrows(IllegalArgumentException.class, () -> badTop.run(runContext()));
        wm.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void throttlingErrorMentionsRetryAfter() throws Exception {
        wm.stubFor(get(urlPathEqualTo("%s/security/alerts_v2".formatted(GRAPH)))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"TooManyRequests\",\"message\":\"Too many requests\"}}")));

        var task = withWireMock(ListAlerts.builder().id(IdUtils.create()).type(ListAlerts.class.getName()).build());
        var e = assertThrows(IllegalStateException.class, () -> task.run(runContext()));

        assertThat(e.getMessage(), containsString("HTTP 429"));
        assertThat(e.getMessage(), containsString("Retry-After: 1s"));
        assertThat(e.getMessage(), containsString("TooManyRequests"));
    }

    @Test
    void getAlert() throws Exception {
        wm.stubFor(get(urlPathEqualTo("%s/security/alerts_v2/da123".formatted(GRAPH)))
            .willReturn(okJson(alertJson("da123", "medium", "inProgress", "2026-10-24T10:00:00Z"))));

        var output = withWireMock(GetAlert.builder()
            .id(IdUtils.create())
            .type(GetAlert.class.getName())
            .alertId(Property.ofValue("da123"))
            .build()).run(runContext());

        assertThat(output.getAlert().get("id"), is("da123"));
        assertThat(output.getAlert().get("status"), is("inProgress"));
        assertThat(output.getAlert().get("title"), is("Suspicious PowerShell"));
    }

    @Test
    void updateAlertSendsOnlySetProperties() throws Exception {
        wm.stubFor(patch(urlPathEqualTo("%s/security/alerts_v2/da123".formatted(GRAPH)))
            .willReturn(okJson(alertJson("da123", "medium", "resolved", "2026-10-24T11:00:00Z"))));

        var task = withWireMock(UpdateAlert.builder().id(IdUtils.create()).type(UpdateAlert.class.getName())
            .alertId(Property.ofValue("da123"))
            .status(Property.ofValue(AlertStatus.RESOLVED))
            .classification(Property.ofValue(Classification.FALSE_POSITIVE))
            .build());

        var output = task.run(runContext());

        assertThat(output.getAlert().get("status"), is("resolved"));
        wm.verify(patchRequestedFor(urlPathEqualTo("%s/security/alerts_v2/da123".formatted(GRAPH)))
            .withRequestBody(matchingJsonPath("$.status", equalTo("resolved")))
            .withRequestBody(matchingJsonPath("$.classification", equalTo("falsePositive")))
            .withRequestBody(notContaining("determination"))
            .withRequestBody(notContaining("assignedTo")));
    }

    @Test
    void updateAlertRequiresAProperty() throws Exception {
        var task = withWireMock(UpdateAlert.builder()
            .id(IdUtils.create())
            .type(UpdateAlert.class.getName())
            .alertId(Property.ofValue("da123"))
            .build());
        assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));

        var badStatus = withWireMock(UpdateAlert.builder()
            .id(IdUtils.create())
            .type(UpdateAlert.class.getName())
            .alertId(Property.ofValue("da123"))
            .status(Property.ofExpression("{{ 'CLOSED' }}"))
            .build());
        assertThrows(IllegalVariableEvaluationException.class, () -> badStatus.run(runContext()));
        wm.verify(0, anyRequestedFor(anyUrl()));
    }
}
