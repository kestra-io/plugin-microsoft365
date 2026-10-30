package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.models.security.AlertClassification;
import com.microsoft.graph.models.security.AlertComment;
import com.microsoft.graph.models.security.AlertDetermination;
import com.microsoft.graph.models.security.Incident;
import com.microsoft.graph.models.security.IncidentStatus;
import com.microsoft.graph.models.odataerrors.ODataError;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import com.microsoft.kiota.HttpMethod;
import com.microsoft.kiota.RequestInformation;
import com.microsoft.kiota.serialization.Parsable;
import com.microsoft.kiota.serialization.ParsableFactory;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Update a Microsoft Defender XDR incident",
    description = "Updates the status, classification, determination, assignee or custom tags of an incident (`PATCH /security/incidents/{id}`), " +
        "only sending the properties that are set, and optionally adds a comment (`POST /security/incidents/{id}/comments`). " +
        "Requires the Microsoft Graph application permission SecurityIncident.ReadWrite.All."
)
@Plugin(
    examples = {
        @Example(
            title = "Enrich an incident with an advanced hunting query, then assign it",
            full = true,
            code = """
                id: defender_enrich_incident
                namespace: company.security

                inputs:
                  - id: incidentId
                    type: STRING
                  - id: deviceName
                    type: STRING

                tasks:
                  - id: hunt
                    type: io.kestra.plugin.microsoft365.defender.HuntingQuery
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    timespan: P1D
                    fetchType: FETCH
                    query: |
                      DeviceProcessEvents
                      | where DeviceName == "{{ inputs.deviceName }}"
                      | where FileName in~ ("powershell.exe", "cmd.exe")
                      | summarize count() by AccountName, FileName

                  - id: assign
                    type: io.kestra.plugin.microsoft365.defender.UpdateIncident
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    incidentId: "{{ inputs.incidentId }}"
                    status: inProgress
                    assignedTo: soc-tier2@company.com
                    customTags:
                      - kestra
                    comment: "Hunting query returned {{ outputs.hunt.count }} suspicious process rows"
                """
        )
    }
)
public class UpdateIncident extends AbstractDefender implements RunnableTask<UpdateIncident.Output> {

    @Schema(title = "Incident ID")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> incidentId;

    @Schema(title = "Status", description = "`active`, `resolved`, `inProgress`, `redirected` or `awaitingAction`.")
    @PluginProperty(group = "main")
    private Property<String> status;

    @Schema(
        title = "Classification",
        description = "`falsePositive`, `truePositive`, `informationalExpectedActivity` or `unknown`."
    )
    @PluginProperty(group = "main")
    private Property<String> classification;

    @Schema(
        title = "Determination",
        description = "For example `malware`, `phishing`, `securityTesting`, `confirmedActivity` or `other`; see the Microsoft Graph `alertDetermination` values."
    )
    @PluginProperty(group = "main")
    private Property<String> determination;

    @Schema(title = "Assigned to", description = "Owner of the incident, usually a user principal name.")
    @PluginProperty(group = "main")
    private Property<String> assignedTo;

    @Schema(
        title = "Custom tags",
        description = "Replaces the custom tags of the incident."
    )
    @PluginProperty(group = "main")
    private Property<List<String>> customTags;

    @Schema(
        title = "Comment",
        description = "Comment added to the incident. Sent with a separate call because Microsoft Graph does not accept comments in the incident update."
    )
    @PluginProperty(group = "main")
    private Property<String> comment;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rIncidentId = runContext.render(incidentId).as(String.class).orElseThrow();

        Incident patch = new Incident();
        boolean changed = false;
        IncidentStatus rStatus = DefenderUtils.parseEnum(
            IncidentStatus.class, runContext.render(status).as(String.class).orElse(null), "status");
        if (rStatus != null) {
            patch.setStatus(rStatus);
            changed = true;
        }
        AlertClassification rClassification = DefenderUtils.parseEnum(
            AlertClassification.class, runContext.render(classification).as(String.class).orElse(null), "classification");
        if (rClassification != null) {
            patch.setClassification(rClassification);
            changed = true;
        }
        AlertDetermination rDetermination = DefenderUtils.parseEnum(
            AlertDetermination.class, runContext.render(determination).as(String.class).orElse(null), "determination");
        if (rDetermination != null) {
            patch.setDetermination(rDetermination);
            changed = true;
        }
        String rAssignedTo = runContext.render(assignedTo).as(String.class).orElse(null);
        if (rAssignedTo != null) {
            patch.setAssignedTo(rAssignedTo);
            changed = true;
        }
        List<String> rCustomTags = runContext.render(customTags).asList(String.class);
        if (customTags != null) {
            patch.setCustomTags(rCustomTags);
            changed = true;
        }
        String rComment = runContext.render(comment).as(String.class).orElse(null);
        if (!changed && rComment == null) {
            throw new IllegalArgumentException("Set at least one of status, classification, determination, assignedTo, customTags or comment");
        }

        GraphServiceClient client = graphClient(runContext);
        var incidentRequest = client.security().incidents().byIncidentId(rIncidentId);
        Incident updated = null;
        try {
            if (changed) {
                updated = incidentRequest.patch(patch);
                runContext.logger().info("Updated Defender incident '{}'", rIncidentId);
            }
            if (rComment != null) {
                addComment(client, rIncidentId, rComment);
                runContext.logger().info("Added a comment to Defender incident '{}'", rIncidentId);
                // Read the incident back so the output includes the new comment
                updated = incidentRequest.get();
            }
        } catch (ApiException e) {
            throw DefenderUtils.graphError(e, "update incident '" + rIncidentId + "'");
        }

        return Output.builder().incident(DefenderUtils.toMap(updated)).build();
    }

    /**
     * POST /security/incidents/{id}/comments. The SDK has no request builder for it, and the alert one sends the comment
     * as a JSON array whereas the endpoint documents a single alertComment object, so the request is built here.
     */
    private static void addComment(GraphServiceClient client, String incidentId, String comment) throws Exception {
        AlertComment alertComment = new AlertComment();
        alertComment.setComment(comment);

        RequestInformation request = new RequestInformation();
        request.httpMethod = HttpMethod.POST;
        request.setUri(new URI(client.getRequestAdapter().getBaseUrl() + "/security/incidents/"
            + URLEncoder.encode(incidentId, StandardCharsets.UTF_8) + "/comments"));
        request.headers.tryAdd("Accept", "application/json");
        request.setContentFromParsable(client.getRequestAdapter(), "application/json", alertComment);

        HashMap<String, ParsableFactory<? extends Parsable>> errorMapping = new HashMap<>();
        errorMapping.put("XXX", ODataError::createFromDiscriminatorValue);
        client.getRequestAdapter().sendCollection(request, errorMapping, AlertComment::createFromDiscriminatorValue);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Updated incident", description = "Incident object returned by Microsoft Graph, with the Graph field names.")
        private final Map<String, Object> incident;
    }
}
