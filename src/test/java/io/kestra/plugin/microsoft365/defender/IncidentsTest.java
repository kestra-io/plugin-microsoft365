package io.kestra.plugin.microsoft365.defender;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class IncidentsTest extends AbstractDefenderTest {

    @Test
    void listIncidentsCapsPageSizeAndExpandsAlerts() throws Exception {
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/incidents"))
            .withQueryParam("$top", equalTo("50"))
            .withQueryParam("$expand", equalTo("alerts"))
            .withQueryParam("$filter", equalTo("(severity eq 'high') and (status eq 'inProgress')"))
            .willReturn(okJson("{\"value\":[" + incidentJson("101", "inProgress", "2026-10-24T10:00:00Z") + "]}")));

        ListIncidents.Output output = withWireMock(ListIncidents.builder()
            .severity(Property.ofValue("high"))
            .status(Property.ofValue("inProgress"))
            .expandAlerts(Property.ofValue(true))
            .top(Property.ofValue(500))
            .build()).run(runContext());

        assertThat(output.getCount(), is(1));
        assertThat(output.getIncidents().getFirst().get("displayName"), is("Multi-stage incident"));
    }

    @Test
    void getIncident() throws Exception {
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/incidents/101"))
            .willReturn(okJson(incidentJson("101", "active", "2026-10-24T10:00:00Z"))));

        GetIncident.Output output = withWireMock(GetIncident.builder().incidentId(Property.ofValue("101")).build()).run(runContext());

        assertThat(output.getIncident().get("id"), is("101"));
        assertThat(output.getIncident().get("customTags"), is(List.of("existing")));
        wm.verify(getRequestedFor(urlPathEqualTo(GRAPH + "/security/incidents/101")).withQueryParam("$expand", absent()));
    }

    @Test
    void updateIncidentPatchesThenPostsCommentAsSingleObject() throws Exception {
        wm.stubFor(patch(urlPathEqualTo(GRAPH + "/security/incidents/101"))
            .willReturn(okJson(incidentJson("101", "inProgress", "2026-10-24T11:00:00Z"))));
        wm.stubFor(post(urlPathEqualTo(GRAPH + "/security/incidents/101/comments"))
            .willReturn(okJson("{\"value\":[{\"comment\":\"Hunting returned 3 rows\",\"createdByDisplayName\":\"app\"}]}")));
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/incidents/101"))
            .willReturn(okJson(incidentJson("101", "inProgress", "2026-10-24T11:00:01Z")
                .replace("}", ",\"comments\":[{\"comment\":\"Hunting returned 3 rows\"}]}"))));

        UpdateIncident.Output output = withWireMock(UpdateIncident.builder()
            .incidentId(Property.ofValue("101"))
            .status(Property.ofValue("inProgress"))
            .assignedTo(Property.ofValue("soc-tier2@company.com"))
            .customTags(Property.ofValue(List.of("kestra")))
            .comment(Property.ofValue("Hunting returned 3 rows"))
            .build()).run(runContext());

        wm.verify(patchRequestedFor(urlPathEqualTo(GRAPH + "/security/incidents/101"))
            .withRequestBody(matchingJsonPath("$.status", equalTo("inProgress")))
            .withRequestBody(matchingJsonPath("$.assignedTo", equalTo("soc-tier2@company.com")))
            .withRequestBody(matchingJsonPath("$.customTags[0]", equalTo("kestra")))
            .withRequestBody(notContaining("classification"))
            .withRequestBody(notContaining("comment")));
        wm.verify(postRequestedFor(urlPathEqualTo(GRAPH + "/security/incidents/101/comments"))
            .withRequestBody(matchingJsonPath("$.comment", equalTo("Hunting returned 3 rows"))));
        assertThat(((List<?>) output.getIncident().get("comments")), hasSize(1));
    }

    @Test
    void updateIncidentWithOnlyCommentSkipsPatch() throws Exception {
        wm.stubFor(post(urlPathEqualTo(GRAPH + "/security/incidents/101/comments"))
            .willReturn(okJson("{\"value\":[]}")));
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/incidents/101"))
            .willReturn(okJson(incidentJson("101", "active", "2026-10-24T11:00:00Z"))));

        UpdateIncident.Output output = withWireMock(UpdateIncident.builder()
            .incidentId(Property.ofValue("101"))
            .comment(Property.ofValue("Looking into it"))
            .build()).run(runContext());

        assertThat(output.getIncident().get("id"), is("101"));
        wm.verify(0, patchRequestedFor(anyUrl()));
    }

    @Test
    void updateIncidentRequiresAProperty() throws Exception {
        var task = withWireMock(UpdateIncident.builder().incidentId(Property.ofValue("101")).build());
        assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void notFoundIsReported() throws Exception {
        wm.stubFor(get(urlPathEqualTo(GRAPH + "/security/incidents/999"))
            .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"NotFound\",\"message\":\"Incident not found\"}}")));

        var task = withWireMock(GetIncident.builder().incidentId(Property.ofValue("999")).build());
        var e = assertThrows(IllegalStateException.class, () -> task.run(runContext()));
        assertThat(e.getMessage(), allOf(containsString("HTTP 404"), containsString("Incident not found")));
    }
}
