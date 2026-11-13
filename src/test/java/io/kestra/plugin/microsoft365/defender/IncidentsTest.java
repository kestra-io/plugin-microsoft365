package io.kestra.plugin.microsoft365.defender;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
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
        wm.stubFor(get(urlPathEqualTo("%s/security/incidents".formatted(GRAPH)))
            .withQueryParam("$top", equalTo("50"))
            .withQueryParam("$expand", equalTo("alerts"))
            .withQueryParam("$filter", equalTo("(severity eq 'high') and (status eq 'inProgress')"))
            .willReturn(okJson("{\"value\":[%s]}".formatted(incidentJson("101", "inProgress", "2026-10-24T10:00:00Z")))));

        var output = withWireMock(ListIncidents.builder().id(IdUtils.create()).type(ListIncidents.class.getName())
            .severity(Property.ofValue(Severity.HIGH))
            .status(Property.ofValue(IncidentStatus.IN_PROGRESS))
            .expandAlerts(Property.ofValue(true))
            .top(Property.ofValue(500))
            .build()).run(runContext());

        assertThat(output.getCount(), is(1));
        assertThat(output.getIncidents().getFirst().get("displayName"), is("Multi-stage incident"));
    }

    @Test
    void getIncident() throws Exception {
        wm.stubFor(get(urlPathEqualTo("%s/security/incidents/101".formatted(GRAPH)))
            .willReturn(okJson(incidentJson("101", "active", "2026-10-24T10:00:00Z"))));

        var output = withWireMock(GetIncident.builder()
            .id(IdUtils.create())
            .type(GetIncident.class.getName())
            .incidentId(Property.ofValue("101"))
            .build()).run(runContext());

        assertThat(output.getIncident().get("id"), is("101"));
        assertThat(output.getIncident().get("customTags"), is(List.of("existing")));
        wm.verify(getRequestedFor(urlPathEqualTo("%s/security/incidents/101".formatted(GRAPH))).withQueryParam("$expand", absent()));
    }

    @Test
    void updateIncidentPatchesThenPostsCommentAsSingleObject() throws Exception {
        wm.stubFor(patch(urlPathEqualTo("%s/security/incidents/101".formatted(GRAPH)))
            .willReturn(okJson(incidentJson("101", "inProgress", "2026-10-24T11:00:00Z"))));
        wm.stubFor(post(urlPathEqualTo("%s/security/incidents/101/comments".formatted(GRAPH)))
            .willReturn(okJson("{\"value\":[{\"comment\":\"Hunting returned 3 rows\",\"createdByDisplayName\":\"app\"}]}")));
        wm.stubFor(get(urlPathEqualTo("%s/security/incidents/101".formatted(GRAPH)))
            .willReturn(okJson(incidentJson("101", "inProgress", "2026-10-24T11:00:01Z")
                .replace("}", ",\"comments\":[{\"comment\":\"Hunting returned 3 rows\"}]}"))));

        var output = withWireMock(UpdateIncident.builder().id(IdUtils.create()).type(UpdateIncident.class.getName())
            .incidentId(Property.ofValue("101"))
            .status(Property.ofValue(IncidentStatus.IN_PROGRESS))
            .assignedTo(Property.ofValue("soc-tier2@company.com"))
            .customTags(Property.ofValue(List.of("kestra")))
            .comment(Property.ofValue("Hunting returned 3 rows"))
            .build()).run(runContext());

        wm.verify(patchRequestedFor(urlPathEqualTo("%s/security/incidents/101".formatted(GRAPH)))
            .withRequestBody(matchingJsonPath("$.status", equalTo("inProgress")))
            .withRequestBody(matchingJsonPath("$.assignedTo", equalTo("soc-tier2@company.com")))
            .withRequestBody(matchingJsonPath("$.customTags[0]", equalTo("kestra")))
            .withRequestBody(notContaining("classification"))
            .withRequestBody(notContaining("comment")));
        wm.verify(postRequestedFor(urlPathEqualTo("%s/security/incidents/101/comments".formatted(GRAPH)))
            .withRequestBody(matchingJsonPath("$.comment", equalTo("Hunting returned 3 rows"))));
        assertThat(((List<?>) output.getIncident().get("comments")), hasSize(1));
    }

    @Test
    void updateIncidentWithOnlyCommentSkipsPatch() throws Exception {
        wm.stubFor(post(urlPathEqualTo("%s/security/incidents/101/comments".formatted(GRAPH)))
            .willReturn(okJson("{\"value\":[]}")));
        wm.stubFor(get(urlPathEqualTo("%s/security/incidents/101".formatted(GRAPH)))
            .willReturn(okJson(incidentJson("101", "active", "2026-10-24T11:00:00Z"))));

        var output = withWireMock(UpdateIncident.builder().id(IdUtils.create()).type(UpdateIncident.class.getName())
            .incidentId(Property.ofValue("101"))
            .comment(Property.ofValue("Looking into it"))
            .build()).run(runContext());

        assertThat(output.getIncident().get("id"), is("101"));
        wm.verify(0, patchRequestedFor(anyUrl()));
    }

    @Test
    void failedCommentAfterAnUpdateSaysTheUpdateWasApplied() throws Exception {
        wm.stubFor(patch(urlPathEqualTo("%s/security/incidents/101".formatted(GRAPH)))
            .willReturn(okJson(incidentJson("101", "inProgress", "2026-10-24T11:00:00Z"))));
        wm.stubFor(post(urlPathEqualTo("%s/security/incidents/101/comments".formatted(GRAPH)))
            .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"BadRequest\",\"message\":\"Comment is too long\"}}")));

        var task = withWireMock(UpdateIncident.builder()
            .id(IdUtils.create())
            .type(UpdateIncident.class.getName())
            .incidentId(Property.ofValue("101"))
            .status(Property.ofValue(IncidentStatus.IN_PROGRESS))
            .comment(Property.ofValue("Hunting returned 3 rows"))
            .build());
        var e = assertThrows(IllegalStateException.class, () -> task.run(runContext()));

        assertThat(e.getMessage(), allOf(
            containsString("add the comment to incident '101' (HTTP 400)"),
            containsString("Comment is too long"),
            endsWith("The incident update was applied, only the comment is missing.")));
        wm.verify(1, patchRequestedFor(urlPathEqualTo("%s/security/incidents/101".formatted(GRAPH))));
        wm.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void failedReadBackAfterTheCommentDoesNotFailTheTask() throws Exception {
        wm.stubFor(patch(urlPathEqualTo("%s/security/incidents/101".formatted(GRAPH)))
            .willReturn(okJson(incidentJson("101", "inProgress", "2026-10-24T11:00:00Z"))));
        wm.stubFor(post(urlPathEqualTo("%s/security/incidents/101/comments".formatted(GRAPH)))
            .willReturn(okJson("{\"value\":[]}")));
        wm.stubFor(get(urlPathEqualTo("%s/security/incidents/101".formatted(GRAPH)))
            .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"NotFound\",\"message\":\"Incident not found\"}}")));

        // Failing here would make a retry post the comment twice
        var output = withWireMock(UpdateIncident.builder()
            .id(IdUtils.create())
            .type(UpdateIncident.class.getName())
            .incidentId(Property.ofValue("101"))
            .status(Property.ofValue(IncidentStatus.IN_PROGRESS))
            .comment(Property.ofValue("Hunting returned 3 rows"))
            .build()).run(runContext());

        assertThat(output.getIncident().get("status"), is("inProgress"));
        wm.verify(1, postRequestedFor(urlPathEqualTo("%s/security/incidents/101/comments".formatted(GRAPH))));
    }

    @Test
    void updateIncidentRequiresAProperty() throws Exception {
        var task = withWireMock(UpdateIncident.builder()
            .id(IdUtils.create())
            .type(UpdateIncident.class.getName())
            .incidentId(Property.ofValue("101"))
            .build());
        assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void notFoundIsReported() throws Exception {
        wm.stubFor(get(urlPathEqualTo("%s/security/incidents/999".formatted(GRAPH)))
            .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"NotFound\",\"message\":\"Incident not found\"}}")));

        var task = withWireMock(GetIncident.builder()
            .id(IdUtils.create())
            .type(GetIncident.class.getName())
            .incidentId(Property.ofValue("999"))
            .build());
        var e = assertThrows(IllegalStateException.class, () -> task.run(runContext()));
        assertThat(e.getMessage(), allOf(containsString("HTTP 404"), containsString("Incident not found")));
    }
}
