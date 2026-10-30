package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.models.security.Incident;
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
    title = "Get a Microsoft Defender XDR incident",
    description = "Gets one incident from the Microsoft Graph security API (`/security/incidents/{id}`). " +
        "Requires the Microsoft Graph application permission SecurityIncident.Read.All."
)
@Plugin(
    examples = {
        @Example(
            title = "Get an incident with its alerts",
            full = true,
            code = """
                id: defender_get_incident
                namespace: company.security

                inputs:
                  - id: incidentId
                    type: STRING

                tasks:
                  - id: get_incident
                    type: io.kestra.plugin.microsoft365.defender.GetIncident
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    incidentId: "{{ inputs.incidentId }}"
                    expandAlerts: true

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.get_incident.incident.displayName }} has {{ outputs.get_incident.incident.alerts | length }} alerts"
                """
        )
    }
)
public class GetIncident extends AbstractDefender implements RunnableTask<GetIncident.Output> {

    @Schema(title = "Incident ID")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> incidentId;

    @Schema(
        title = "Include alerts",
        description = "Expand the alerts of the incident (`$expand=alerts`)."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Boolean> expandAlerts = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rIncidentId = runContext.render(incidentId).as(String.class).orElseThrow();
        boolean rExpandAlerts = runContext.render(expandAlerts).as(Boolean.class).orElse(false);

        Incident incident;
        try {
            incident = graphClient(runContext).security().incidents().byIncidentId(rIncidentId).get(config -> {
                if (rExpandAlerts) {
                    config.queryParameters.expand = new String[]{"alerts"};
                }
            });
        } catch (ApiException e) {
            throw DefenderUtils.graphError(e, "get incident '" + rIncidentId + "'");
        }

        return Output.builder().incident(DefenderUtils.toMap(incident)).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Incident", description = "Incident object with the Microsoft Graph field names.")
        private final Map<String, Object> incident;
    }
}
