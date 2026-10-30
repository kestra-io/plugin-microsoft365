package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.models.security.Alert;
import com.microsoft.graph.models.security.AlertCollectionResponse;
import com.microsoft.graph.models.security.AlertSeverity;
import com.microsoft.graph.models.security.Incident;
import com.microsoft.graph.models.security.IncidentCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValue;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.microsoft365.AbstractMicrosoft365Trigger;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger on new or updated Microsoft Defender XDR alerts or incidents",
    description = "Polls `/security/alerts_v2` or `/security/incidents` for items whose `lastUpdateDateTime` is after a watermark kept in the namespace KV store " +
        "(key `defender_watermark_<flowId length>_<flowId>_<triggerId>`), and starts one execution with all of them. " +
        "The watermark is moved to the latest `lastUpdateDateTime` seen. On the first poll the watermark is set to the current time and no execution is started, " +
        "so existing items are not replayed. Requires the Microsoft Graph application permission SecurityAlert.Read.All or SecurityIncident.Read.All."
)
@Plugin(
    examples = {
        @Example(
            title = "Start a flow on every new or updated high-severity incident",
            full = true,
            code = """
                id: defender_on_new_incident
                namespace: company.security

                triggers:
                  - id: on_incident
                    type: io.kestra.plugin.microsoft365.defender.Trigger
                    interval: PT1M
                    resource: INCIDENTS
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    severity: high

                tasks:
                  - id: notify
                    type: io.kestra.plugin.core.log.Log
                    message: "New incident(s): {{ trigger.items | jq('[.[].displayName]') | first }}"
                """
        ),
        @Example(
            title = "Start a flow on new Defender for Endpoint alerts",
            full = true,
            code = """
                id: defender_on_endpoint_alert
                namespace: company.security

                triggers:
                  - id: on_alert
                    type: io.kestra.plugin.microsoft365.defender.Trigger
                    interval: PT5M
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    filter: "serviceSource eq 'microsoftDefenderForEndpoint'"

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.count }} new or updated alerts"
                """
        )
    }
)
public class Trigger extends AbstractMicrosoft365Trigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {
    // OData datetime literal with the 7 fractional digits used by Microsoft Graph
    private static final DateTimeFormatter WATERMARK_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'").withZone(ZoneOffset.UTC);

    public enum Resource {
        ALERTS,
        INCIDENTS
    }

    @Schema(title = "Resource to watch", description = "`ALERTS` (default) or `INCIDENTS`.")
    @NotNull
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Resource> resource = Property.ofValue(Resource.ALERTS);

    @Schema(
        title = "OData filter",
        description = "Raw OData `$filter` expression added to the watermark filter with `and`, e.g. `serviceSource eq 'microsoftDefenderForEndpoint'`."
    )
    @PluginProperty(group = "processing")
    private Property<String> filter;

    @Schema(
        title = "Severity",
        description = "Only fire for items with this severity: `informational`, `low`, `medium` or `high`."
    )
    @PluginProperty(group = "processing")
    private Property<String> severity;

    @Schema(title = "Polling interval", description = "ISO-8601 duration between two polls.")
    @Builder.Default
    @PluginProperty(group = "execution")
    private Duration interval = Duration.ofMinutes(1);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();

        Resource rResource = runContext.render(resource).as(Resource.class).orElse(Resource.ALERTS);
        String rFilter = runContext.render(filter).as(String.class).orElse(null);
        AlertSeverity rSeverity = DefenderUtils.parseEnum(
            AlertSeverity.class, runContext.render(severity).as(String.class).orElse(null), "severity");

        KVStore kv = runContext.namespaceKv(context.getNamespace());
        String key = watermarkKey(context.getFlowId(), this.id);
        Optional<Instant> watermark = kv.getValue(key).map(KVValue::value).map(v -> Instant.parse(v.toString()));

        if (watermark.isEmpty()) {
            Instant now = Instant.now();
            saveWatermark(kv, key, now);
            runContext.logger().info("First poll: Defender {} watermark set to {}, existing items are not replayed", rResource, now);
            return Optional.empty();
        }

        String query = DefenderUtils.combineFilters(
            "lastUpdateDateTime gt " + WATERMARK_FORMAT.format(watermark.get()),
            rFilter,
            DefenderUtils.eqClause("severity", rSeverity)
        );
        runContext.logger().debug("Polling Defender {} with filter: {}", rResource, query);

        GraphServiceClient client = graphClient(runContext);
        List<Map<String, Object>> items;
        Instant newWatermark = watermark.get();
        try {
            // Graph lists most recent first and has no documented $orderby, so read every page before moving the watermark
            if (rResource == Resource.ALERTS) {
                List<Alert> alerts = DefenderUtils.collectPages(
                    client.security().alertsV2().get(config -> config.queryParameters.filter = query),
                    AlertCollectionResponse::getValue,
                    AlertCollectionResponse::getOdataNextLink,
                    next -> client.security().alertsV2().withUrl(next).get(),
                    Integer.MAX_VALUE
                );
                for (Alert alert : alerts) {
                    newWatermark = latest(newWatermark, alert.getLastUpdateDateTime());
                }
                items = DefenderUtils.toMaps(alerts);
            } else {
                List<Incident> incidents = DefenderUtils.collectPages(
                    client.security().incidents().get(config -> config.queryParameters.filter = query),
                    IncidentCollectionResponse::getValue,
                    IncidentCollectionResponse::getOdataNextLink,
                    next -> client.security().incidents().withUrl(next).get(),
                    Integer.MAX_VALUE
                );
                for (Incident incident : incidents) {
                    newWatermark = latest(newWatermark, incident.getLastUpdateDateTime());
                }
                items = DefenderUtils.toMaps(incidents);
            }
        } catch (ApiException e) {
            if (DefenderUtils.isTransient(e)) {
                // The watermark is left untouched so the next poll reads the same items again
                runContext.logger().warn("Microsoft Graph is throttling or unavailable (HTTP {}), skipping this poll", e.getResponseStatusCode());
                return Optional.empty();
            }
            throw DefenderUtils.graphError(e, "list " + rResource.name().toLowerCase());
        }

        if (items.isEmpty()) {
            return Optional.empty();
        }

        saveWatermark(kv, key, newWatermark);
        runContext.logger().info("{} new or updated Defender {}, watermark moved to {}", items.size(), rResource, newWatermark);

        Output output = Output.builder()
            .items(items)
            .count(items.size())
            .build();
        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }

    static String watermarkKey(String flowId, String triggerId) {
        return "defender_watermark_" + flowId.length() + "_" + flowId + "_" + triggerId;
    }

    private static Instant latest(Instant current, OffsetDateTime candidate) {
        if (candidate == null) {
            return current;
        }
        Instant instant = candidate.toInstant();
        return instant.isAfter(current) ? instant : current;
    }

    private static void saveWatermark(KVStore kv, String key, Instant watermark) throws java.io.IOException {
        kv.put(key, new KVValueAndMetadata(new KVMetadata("Microsoft Defender trigger watermark (lastUpdateDateTime)", (Duration) null), watermark.toString()));
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "New or updated alerts or incidents", description = "Objects with the Microsoft Graph field names.")
        private final List<Map<String, Object>> items;

        @Schema(title = "Number of items")
        private final int count;
    }
}
