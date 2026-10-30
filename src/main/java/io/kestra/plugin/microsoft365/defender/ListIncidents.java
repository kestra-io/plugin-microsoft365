package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.models.security.Incident;
import com.microsoft.graph.models.security.IncidentCollectionResponse;
import com.microsoft.graph.models.security.IncidentStatus;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
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
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Microsoft Defender XDR incidents",
    description = "Lists incidents from the Microsoft Graph security API (`/security/incidents`), most recent first, following `@odata.nextLink` until `top` incidents are collected. " +
        "Requires the Microsoft Graph application permission SecurityIncident.Read.All."
)
@Plugin(
    examples = {
        @Example(
            title = "List active high-severity incidents with their alerts",
            full = true,
            code = """
                id: defender_list_incidents
                namespace: company.security

                tasks:
                  - id: list_incidents
                    type: io.kestra.plugin.microsoft365.defender.ListIncidents
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    severity: high
                    status: active
                    expandAlerts: true
                    top: 20

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.list_incidents.incidents | jq('[.[].displayName]') | first }}"
                """
        )
    }
)
public class ListIncidents extends AbstractDefender implements RunnableTask<ListIncidents.Output> {
    // Microsoft Graph returns at most 50 incidents per page
    private static final int PAGE_SIZE = 50;

    @Schema(
        title = "OData filter",
        description = "Raw OData `$filter` expression, e.g. `assignedTo eq 'soc@company.com'`. Combined with `severity` and `status` using `and`."
    )
    @PluginProperty(group = "processing")
    private Property<String> filter;

    @Schema(
        title = "Severity",
        description = "Only return incidents with this severity: `informational`, `low`, `medium` or `high`."
    )
    @PluginProperty(group = "processing")
    private Property<String> severity;

    @Schema(
        title = "Status",
        description = "Only return incidents with this status: `active`, `resolved`, `inProgress`, `redirected` or `awaitingAction`."
    )
    @PluginProperty(group = "processing")
    private Property<String> status;

    @Schema(
        title = "Include alerts",
        description = "Expand the alerts of each incident (`$expand=alerts`)."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Boolean> expandAlerts = Property.ofValue(false);

    @Schema(
        title = "Maximum number of incidents",
        description = "Between 1 and 2000."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> top = Property.ofValue(DEFAULT_TOP);

    @Schema(
        title = "How to output the incidents",
        description = """
            FETCH - outputs the incidents as `incidents`
            FETCH_ONE - outputs the first incident as `incident`
            STORE - stores the incidents in an ION file and outputs its `uri`
            NONE - only outputs `count`"""
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        int rTop = renderTop(runContext, top);
        IncidentStatus rStatus = DefenderUtils.parseEnum(
            IncidentStatus.class, runContext.render(status).as(String.class).orElse(null), "status");
        String rFilter = renderFilter(runContext, filter, severity, DefenderUtils.eqClause("status", rStatus));
        boolean rExpandAlerts = runContext.render(expandAlerts).as(Boolean.class).orElse(false);
        FetchType rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        runContext.logger().info("Listing up to {} Defender incidents (filter: {})", rTop, rFilter);

        GraphServiceClient client = graphClient(runContext);
        List<Incident> incidents;
        try {
            IncidentCollectionResponse firstPage = client.security().incidents().get(config -> {
                config.queryParameters.top = Math.min(rTop, PAGE_SIZE);
                config.queryParameters.filter = rFilter;
                if (rExpandAlerts) {
                    config.queryParameters.expand = new String[]{"alerts"};
                }
            });
            incidents = DefenderUtils.collectPages(
                firstPage,
                IncidentCollectionResponse::getValue,
                IncidentCollectionResponse::getOdataNextLink,
                next -> client.security().incidents().withUrl(next).get(),
                rTop
            );
        } catch (ApiException e) {
            throw DefenderUtils.graphError(e, "list incidents");
        }

        runContext.logger().info("Retrieved {} Defender incidents", incidents.size());

        Fetched fetched = fetch(runContext, rFetchType, DefenderUtils.toMaps(incidents));
        return Output.builder()
            .incidents(fetched.items())
            .incident(fetched.first())
            .uri(fetched.uri())
            .count(incidents.size())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Incidents (FETCH)", description = "Incident objects with the Microsoft Graph field names.")
        private final List<Map<String, Object>> incidents;

        @Schema(title = "First incident (FETCH_ONE)")
        private final Map<String, Object> incident;

        @Schema(title = "URI of the ION file with the incidents (STORE)")
        private final URI uri;

        @Schema(title = "Number of incidents retrieved")
        private final int count;
    }
}
