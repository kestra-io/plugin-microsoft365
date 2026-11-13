package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.models.security.AlertComment;
import com.microsoft.graph.models.security.Incident;
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
    description = """
        Updates the status, classification, determination, assignee or custom tags of an incident (`PATCH
        /security/incidents/{id}`), only sending the properties that are set, and optionally adds a comment (`POST
        /security/incidents/{id}/comments`). Requires the Microsoft Graph application permission
        SecurityIncident.ReadWrite.All.
        """
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
                    # Device names only, so the input cannot change the KQL query below
                    validator: "^[A-Za-z0-9._-]+$"

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
                    status: IN_PROGRESS
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

    @Schema(title = "Status", description = "Allowed values: `ACTIVE`, `IN_PROGRESS`, `RESOLVED`, `REDIRECTED`, `AWAITING_ACTION`.")
    @PluginProperty(group = "main")
    private Property<IncidentStatus> status;

    @Schema(
        title = "Classification",
        description = """
            How the item was classified after investigation. Allowed values: `UNKNOWN`, `FALSE_POSITIVE`, `TRUE_POSITIVE`,
            `INFORMATIONAL_EXPECTED_ACTIVITY`.
            """
    )
    @PluginProperty(group = "main")
    private Property<Classification> classification;

    @Schema(
        title = "Determination",
        description = """
            What the investigation determined. Allowed values: `UNKNOWN`, `APT`, `MALWARE`, `SECURITY_PERSONNEL`,
            `SECURITY_TESTING`, `UNWANTED_SOFTWARE`, `OTHER`, `MULTI_STAGED_ATTACK`, `COMPROMISED_ACCOUNT`, `PHISHING`,
            `MALICIOUS_USER_ACTIVITY`, `NOT_MALICIOUS`, `NOT_ENOUGH_DATA_TO_VALIDATE`, `CONFIRMED_ACTIVITY`,
            `LINE_OF_BUSINESS_APPLICATION`.
            """
    )
    @PluginProperty(group = "main")
    private Property<Determination> determination;

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
        var rIncidentId = runContext.render(incidentId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("incidentId is required"));

        var patch = new Incident();
        var changed = false;
        var rStatus = runContext.render(status).as(IncidentStatus.class).orElse(null);
        if (rStatus != null) {
            patch.setStatus(rStatus.toGraph());
            changed = true;
        }
        var rClassification = runContext.render(classification).as(Classification.class).orElse(null);
        if (rClassification != null) {
            patch.setClassification(rClassification.toGraph());
            changed = true;
        }
        var rDetermination = runContext.render(determination).as(Determination.class).orElse(null);
        if (rDetermination != null) {
            patch.setDetermination(rDetermination.toGraph());
            changed = true;
        }
        var rAssignedTo = runContext.render(assignedTo).as(String.class).orElse(null);
        if (rAssignedTo != null) {
            patch.setAssignedTo(rAssignedTo);
            changed = true;
        }
        var rCustomTags = runContext.render(customTags).asList(String.class);
        if (customTags != null) {
            patch.setCustomTags(rCustomTags);
            changed = true;
        }
        var rComment = runContext.render(comment).as(String.class).orElse(null);
        if (!changed && rComment == null) {
            throw new IllegalArgumentException("Set at least one of status, classification, determination, assignedTo, customTags or comment");
        }

        var client = graphClient(runContext);
        var incidentRequest = client.security().incidents().byIncidentId(rIncidentId);
        Incident updated = null;
        if (changed) {
            try {
                updated = incidentRequest.patch(patch);
            } catch (ApiException e) {
                throw DefenderUtils.graphError(e, "update incident '%s'".formatted(rIncidentId));
            }
            runContext.logger().info("Updated Defender incident '{}'", rIncidentId);
        }
        if (rComment != null) {
            try {
                addComment(client, rIncidentId, rComment);
            } catch (ApiException e) {
                var error = DefenderUtils.graphError(e, "add the comment to incident '%s'".formatted(rIncidentId));
                if (changed) {
                    throw new IllegalStateException("%s The incident update was applied, only the comment is missing.".formatted(error.getMessage()), e);
                }
                throw error;
            }
            runContext.logger().info("Added a comment to Defender incident '{}'", rIncidentId);
            // The comment is posted, so a failed read-back must not fail the task: a retry would post it twice
            try {
                updated = incidentRequest.get();
            } catch (ApiException e) {
                runContext.logger().warn("Added the comment to Defender incident '{}' but could not read the incident back: {}",
                    rIncidentId, DefenderUtils.graphError(e, "get incident '%s'".formatted(rIncidentId)).getMessage());
            }
        }

        return Output.builder().incident(DefenderUtils.toMap(updated)).build();
    }

    // The SDK has no builder for POST /security/incidents/{id}/comments that sends the single alertComment object the endpoint documents
    private static void addComment(GraphServiceClient client, String incidentId, String comment) throws Exception {
        var alertComment = new AlertComment();
        alertComment.setComment(comment);

        var request = new RequestInformation();
        request.httpMethod = HttpMethod.POST;
        request.setUri(new URI("%s/security/incidents/%s/comments".formatted(
            client.getRequestAdapter().getBaseUrl(), URLEncoder.encode(incidentId, StandardCharsets.UTF_8))));
        request.headers.tryAdd("Accept", "application/json");
        request.setContentFromParsable(client.getRequestAdapter(), "application/json", alertComment);

        var errorMapping = new HashMap<String, ParsableFactory<? extends Parsable>>();
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
