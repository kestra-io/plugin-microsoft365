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
    description = "Updates the status, classification, determination or assignee of an alert (`PATCH /security/alerts_v2/{id}`). " +
        "Only the properties that are set are sent. Requires the Microsoft Graph application permission SecurityAlert.ReadWrite.All."
)
@Plugin(
    examples = {
        @Example(
            title = "Resolve an alert as a false positive",
            full = true,
            code = "id: defender_resolve_alert\n"
                + "namespace: company.security\n"
                + "\n"
                + "inputs:\n"
                + "  - id: alertId\n"
                + "    type: STRING\n"
                + "\n"
                + "tasks:\n"
                + "  - id: resolve\n"
                + "    type: io.kestra.plugin.microsoft365.defender.UpdateAlert\n"
                + "    tenantId: \"{{ secret('AZURE_TENANT_ID') }}\"\n"
                + "    clientId: \"{{ secret('AZURE_CLIENT_ID') }}\"\n"
                + "    clientSecret: \"{{ secret('AZURE_CLIENT_SECRET') }}\"\n"
                + "    alertId: \"{{ inputs.alertId }}\"\n"
                + "    status: RESOLVED\n"
                + "    classification: FALSE_POSITIVE\n"
                + "    determination: OTHER\n"
                + "    assignedTo: soc-tier1@company.com\n"
        )
    }
)
public class UpdateAlert extends AbstractDefender implements RunnableTask<UpdateAlert.Output> {

    @Schema(title = "Alert ID")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> alertId;

    @Schema(title = "Status")
    @PluginProperty(group = "main")
    private Property<AlertStatus> status;

    @Schema(
        title = "Classification",
        description = "How the item was classified after investigation."
    )
    @PluginProperty(group = "main")
    private Property<Classification> classification;

    @Schema(
        title = "Determination",
        description = "What the investigation determined, for example `MALWARE`, `PHISHING` or `SECURITY_TESTING`."
    )
    @PluginProperty(group = "main")
    private Property<Determination> determination;

    @Schema(title = "Assigned to", description = "Owner of the alert, usually a user principal name.")
    @PluginProperty(group = "main")
    private Property<String> assignedTo;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rAlertId = runContext.render(alertId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("alertId is required"));

        Alert patch = new Alert();
        boolean changed = false;
        AlertStatus rStatus = runContext.render(status).as(AlertStatus.class).orElse(null);
        if (rStatus != null) {
            patch.setStatus(rStatus.toGraph());
            changed = true;
        }
        Classification rClassification = runContext.render(classification).as(Classification.class).orElse(null);
        if (rClassification != null) {
            patch.setClassification(rClassification.toGraph());
            changed = true;
        }
        Determination rDetermination = runContext.render(determination).as(Determination.class).orElse(null);
        if (rDetermination != null) {
            patch.setDetermination(rDetermination.toGraph());
            changed = true;
        }
        String rAssignedTo = runContext.render(assignedTo).as(String.class).orElse(null);
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
            throw DefenderUtils.graphError(e, "update alert '" + rAlertId + "'");
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
