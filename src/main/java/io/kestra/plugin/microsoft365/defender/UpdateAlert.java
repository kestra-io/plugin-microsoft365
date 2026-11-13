package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.models.security.Alert;
import com.microsoft.kiota.ApiException;
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

import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Update a Microsoft Defender XDR alert",
    description = """
        Updates the status, classification, determination or assignee of an alert (`PATCH /security/alerts_v2/{id}`).
        Only the properties that are set are sent. Requires the Microsoft Graph application permission
        SecurityAlert.ReadWrite.All.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Resolve an alert as a false positive",
            full = true,
            code = """
                id: defender_resolve_alert
                namespace: company.security

                inputs:
                  - id: alertId
                    type: STRING

                tasks:
                  - id: resolve
                    type: io.kestra.plugin.microsoft365.defender.UpdateAlert
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    alertId: "{{ inputs.alertId }}"
                    status: RESOLVED
                    classification: FALSE_POSITIVE
                    determination: OTHER
                    assignedTo: soc-tier1@company.com
                """
        )
    }
)
public class UpdateAlert extends AbstractDefender implements RunnableTask<UpdateAlert.Output> {

    @Schema(title = "Alert ID")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> alertId;

    @Schema(title = "Status", description = "Allowed values: `NEW`, `IN_PROGRESS`, `RESOLVED`.")
    @PluginProperty(group = "main")
    private Property<AlertStatus> status;

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

    @Schema(title = "Assigned to", description = "Owner of the alert, usually a user principal name.")
    @PluginProperty(group = "main")
    private Property<String> assignedTo;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rAlertId = runContext.render(alertId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("alertId is required"));

        var patch = new Alert();
        var changed = false;
        var rStatus = runContext.render(status).as(AlertStatus.class).orElse(null);
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
        if (!changed) {
            throw new IllegalArgumentException("Set at least one of status, classification, determination or assignedTo");
        }

        Alert updated;
        try {
            updated = graphClient(runContext).security().alertsV2().byAlertId(rAlertId).patch(patch);
        } catch (ApiException e) {
            throw DefenderUtils.graphError(e, "update alert '%s'".formatted(rAlertId));
        }
        runContext.logger().info("Updated Defender alert '{}'", rAlertId);

        return Output.builder().alert(DefenderUtils.toMap(updated)).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Updated alert", description = "Alert object returned by Microsoft Graph, with the Graph field names.")
        private final Map<String, Object> alert;
    }
}
