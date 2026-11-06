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
    title = "Get a Microsoft Defender XDR alert",
    description = "Gets one alert from the Microsoft Graph security API (`/security/alerts_v2/{id}`), including its evidence. " +
        "Requires the Microsoft Graph application permission SecurityAlert.Read.All."
)
@Plugin(
    examples = {
        @Example(
            title = "Get an alert and log its title",
            full = true,
            code = "id: defender_get_alert\n"
                + "namespace: company.security\n"
                + "\n"
                + "inputs:\n"
                + "  - id: alertId\n"
                + "    type: STRING\n"
                + "\n"
                + "tasks:\n"
                + "  - id: get_alert\n"
                + "    type: io.kestra.plugin.microsoft365.defender.GetAlert\n"
                + "    tenantId: \"{{ secret('AZURE_TENANT_ID') }}\"\n"
                + "    clientId: \"{{ secret('AZURE_CLIENT_ID') }}\"\n"
                + "    clientSecret: \"{{ secret('AZURE_CLIENT_SECRET') }}\"\n"
                + "    alertId: \"{{ inputs.alertId }}\"\n"
                + "\n"
                + "  - id: log\n"
                + "    type: io.kestra.plugin.core.log.Log\n"
                + "    message: \"{{ outputs.get_alert.alert.title }} ({{ outputs.get_alert.alert.severity }})\"\n"
        )
    }
)
public class GetAlert extends AbstractDefender implements RunnableTask<GetAlert.Output> {

    @Schema(title = "Alert ID")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> alertId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rAlertId = runContext.render(alertId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("alertId is required"));

        Alert alert;
        try {
            alert = graphClient(runContext).security().alertsV2().byAlertId(rAlertId).get();
        } catch (ApiException e) {
            throw DefenderUtils.graphError(e, "get alert '" + rAlertId + "'");
        }

        return Output.builder().alert(DefenderUtils.toMap(alert)).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Alert", description = "Alert object with the Microsoft Graph field names.")
        private final Map<String, Object> alert;
    }
}
