package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.models.security.Alert;
import com.microsoft.graph.models.security.AlertCollectionResponse;
import com.microsoft.graph.models.security.AlertStatus;
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
    title = "List Microsoft Defender XDR alerts",
    description = "Lists alerts from the Microsoft Graph security API (`/security/alerts_v2`), most recent first, following `@odata.nextLink` until `top` alerts are collected. " +
        "Requires the Microsoft Graph application permission SecurityAlert.Read.All."
)
@Plugin(
    examples = {
        @Example(
            title = "Store all new high-severity alerts in internal storage",
            full = true,
            code = """
                id: defender_list_high_alerts
                namespace: company.security

                inputs:
                  - id: severity
                    type: STRING
                    defaults: high

                tasks:
                  - id: list_alerts
                    type: io.kestra.plugin.microsoft365.defender.ListAlerts
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    severity: "{{ inputs.severity }}"
                    status: new
                    top: 200
                    fetchType: STORE

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.list_alerts.count }} open {{ inputs.severity }} alerts written to {{ outputs.list_alerts.uri }}"
                """
        ),
        @Example(
            title = "List Defender for Endpoint alerts with a raw OData filter",
            full = true,
            code = """
                id: defender_list_endpoint_alerts
                namespace: company.security

                tasks:
                  - id: list_alerts
                    type: io.kestra.plugin.microsoft365.defender.ListAlerts
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    filter: "serviceSource eq 'microsoftDefenderForEndpoint'"
                    top: 50
                    fetchType: FETCH
                """
        )
    }
)
public class ListAlerts extends AbstractDefender implements RunnableTask<ListAlerts.Output> {

    @Schema(
        title = "OData filter",
        description = "Raw OData `$filter` expression, e.g. `serviceSource eq 'microsoftDefenderForEndpoint'`. Combined with `severity` and `status` using `and`."
    )
    @PluginProperty(group = "processing")
    private Property<String> filter;

    @Schema(
        title = "Severity",
        description = "Only return alerts with this severity: `informational`, `low`, `medium` or `high`."
    )
    @PluginProperty(group = "processing")
    private Property<String> severity;

    @Schema(
        title = "Status",
        description = "Only return alerts with this status: `new`, `inProgress` or `resolved`."
    )
    @PluginProperty(group = "processing")
    private Property<String> status;

    @Schema(
        title = "Maximum number of alerts",
        description = "Between 1 and 2000."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> top = Property.ofValue(DEFAULT_TOP);

    @Schema(
        title = "How to output the alerts",
        description = """
            FETCH - outputs the alerts as `alerts`
            FETCH_ONE - outputs the first alert as `alert`
            STORE - stores the alerts in an ION file and outputs its `uri`
            NONE - only outputs `count`"""
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        int rTop = renderTop(runContext, top);
        AlertStatus rStatus = DefenderUtils.parseEnum(
            AlertStatus.class, runContext.render(status).as(String.class).orElse(null), "status");
        String rFilter = renderFilter(runContext, filter, severity, DefenderUtils.eqClause("status", rStatus));
        FetchType rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        runContext.logger().info("Listing up to {} Defender alerts (filter: {})", rTop, rFilter);

        GraphServiceClient client = graphClient(runContext);
        List<Alert> alerts;
        try {
            AlertCollectionResponse firstPage = client.security().alertsV2().get(config -> {
                config.queryParameters.top = rTop;
                config.queryParameters.filter = rFilter;
            });
            alerts = DefenderUtils.collectPages(
                firstPage,
                AlertCollectionResponse::getValue,
                AlertCollectionResponse::getOdataNextLink,
                next -> client.security().alertsV2().withUrl(next).get(),
                rTop
            );
        } catch (ApiException e) {
            throw DefenderUtils.graphError(e, "list alerts");
        }

        runContext.logger().info("Retrieved {} Defender alerts", alerts.size());

        Fetched fetched = fetch(runContext, rFetchType, DefenderUtils.toMaps(alerts));
        return Output.builder()
            .alerts(fetched.items())
            .alert(fetched.first())
            .uri(fetched.uri())
            .count(alerts.size())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Alerts (FETCH)", description = "Alert objects with the Microsoft Graph field names.")
        private final List<Map<String, Object>> alerts;

        @Schema(title = "First alert (FETCH_ONE)")
        private final Map<String, Object> alert;

        @Schema(title = "URI of the ION file with the alerts (STORE)")
        private final URI uri;

        @Schema(title = "Number of alerts retrieved")
        private final int count;
    }
}
