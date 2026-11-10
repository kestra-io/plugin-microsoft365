package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.models.security.Alert;
import com.microsoft.graph.models.security.AlertCollectionResponse;
import com.microsoft.graph.models.security.Incident;
import com.microsoft.graph.models.security.IncidentCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import com.microsoft.kiota.serialization.Parsable;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.StatefulTriggerInterface;
import io.kestra.core.models.triggers.StatefulTriggerService;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.microsoft365.AbstractMicrosoft365Trigger;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger on new or updated Microsoft Defender XDR alerts or incidents",
    description = "Polls `/security/alerts_v2` or `/security/incidents` for items whose `lastUpdateDateTime` is at or after the last one seen minus `lookback`, " +
        "and starts one execution with the items not fired yet, stored as an ION file in internal storage. " +
        "Seen items are kept in the trigger state by id and `lastUpdateDateTime`, so items sharing a timestamp or indexed late are not missed and are not fired twice. " +
        "On the first poll no execution is started, so existing items are not replayed. " +
        "Requires the Microsoft Graph application permission SecurityAlert.Read.All or SecurityIncident.Read.All."
)
@Plugin(
    examples = {
        @Example(
            title = "Start a flow on every new or updated high-severity incident",
            full = true,
            code = "id: defender_on_new_incident\n"
                + "namespace: company.security\n"
                + "\n"
                + "triggers:\n"
                + "  - id: on_incident\n"
                + "    type: io.kestra.plugin.microsoft365.defender.Trigger\n"
                + "    interval: PT1M\n"
                + "    resource: INCIDENTS\n"
                + "    tenantId: \"{{ secret('AZURE_TENANT_ID') }}\"\n"
                + "    clientId: \"{{ secret('AZURE_CLIENT_ID') }}\"\n"
                + "    clientSecret: \"{{ secret('AZURE_CLIENT_SECRET') }}\"\n"
                + "    severity: HIGH\n"
                + "\n"
                + "tasks:\n"
                + "  - id: each_incident\n"
                + "    type: io.kestra.plugin.core.flow.ForEachItem\n"
                + "    items: \"{{ trigger.uri }}\"\n"
                + "    batch:\n"
                + "      rows: 1\n"
                + "    namespace: company.security\n"
                + "    flowId: triage_incident\n"
                + "    inputs:\n"
                + "      incident_file: \"{{ taskrun.items }}\"\n"
        ),
        @Example(
            title = "Start a flow on new Defender for Endpoint alerts only",
            full = true,
            code = "id: defender_on_endpoint_alert\n"
                + "namespace: company.security\n"
                + "\n"
                + "triggers:\n"
                + "  - id: on_alert\n"
                + "    type: io.kestra.plugin.microsoft365.defender.Trigger\n"
                + "    interval: PT5M\n"
                + "    on: CREATE\n"
                + "    tenantId: \"{{ secret('AZURE_TENANT_ID') }}\"\n"
                + "    clientId: \"{{ secret('AZURE_CLIENT_ID') }}\"\n"
                + "    clientSecret: \"{{ secret('AZURE_CLIENT_SECRET') }}\"\n"
                + "    filter: \"serviceSource eq 'microsoftDefenderForEndpoint'\"\n"
                + "\n"
                + "tasks:\n"
                + "  - id: log\n"
                + "    type: io.kestra.plugin.core.log.Log\n"
                + "    message: \"{{ trigger.count }} new alerts stored in {{ trigger.uri }}\"\n"
        )
    }
)
public class Trigger extends AbstractMicrosoft365Trigger implements PollingTriggerInterface, StatefulTriggerInterface, TriggerOutput<Trigger.Output> {
    // OData datetime literal with the 7 fractional digits used by Microsoft Graph
    private static final DateTimeFormatter ODATA_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'").withZone(ZoneOffset.UTC);
    // State entry holding the latest lastUpdateDateTime seen, next to the entries of the seen items
    static final String WATERMARK_KEY = "__lastUpdateDateTime";
    private static final String START_TIME_KEY = "__pollStartDateTime";
    private static final Duration DEFAULT_LOOKBACK = Duration.ofMinutes(5);
    private static final Duration DEFAULT_STATE_TTL = Duration.ofDays(7);

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
        description = "Raw OData `$filter` expression added to the `lastUpdateDateTime` filter with `and`, e.g. `serviceSource eq 'microsoftDefenderForEndpoint'`."
    )
    @PluginProperty(group = "processing")
    private Property<String> filter;

    @Schema(title = "Severity", description = "Only fire for items with this severity.")
    @PluginProperty(group = "processing")
    private Property<Severity> severity;

    @Schema(
        title = "Lookback",
        description = "How far before the latest `lastUpdateDateTime` seen each poll reads again, to catch items indexed late. " +
            "Items already fired are skipped using the trigger state."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Duration> lookback = Property.ofValue(DEFAULT_LOOKBACK);

    @Schema(title = "Polling interval", description = "ISO-8601 duration between two polls.")
    @Builder.Default
    @PluginProperty(group = "execution")
    private Duration interval = Duration.ofMinutes(1);

    @Schema(
        title = "Trigger event type",
        description = "`CREATE` fires for items not seen before, `UPDATE` for seen items with a new `lastUpdateDateTime`, `CREATE_OR_UPDATE` (default) for both."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<On> on = Property.ofValue(On.CREATE_OR_UPDATE);

    @Schema(title = "State key", description = "Key of the trigger state in the namespace KV store; defaults to `<namespace>_<flowId>_<triggerId>`.")
    @PluginProperty(group = "advanced")
    private Property<String> stateKey;

    @Schema(
        title = "State TTL",
        description = "How long a seen item is remembered after its last change; default 7 days. An item updated after that is seen as new."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Duration> stateTtl = Property.ofValue(DEFAULT_STATE_TTL);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();

        Resource rResource = runContext.render(resource).as(Resource.class).orElse(Resource.ALERTS);
        String rFilter = runContext.render(filter).as(String.class).orElse(null);
        Severity rSeverity = runContext.render(severity).as(Severity.class).orElse(null);
        Duration rLookback = runContext.render(lookback).as(Duration.class).orElse(DEFAULT_LOOKBACK);
        On rOn = runContext.render(on).as(On.class).orElse(On.CREATE_OR_UPDATE);
        String rStateKey = runContext.render(stateKey).as(String.class)
            .orElse(StatefulTriggerService.defaultKey(context.getNamespace(), context.getFlowId(), this.id));
        Duration rStateTtl = runContext.render(stateTtl).as(Duration.class).orElse(DEFAULT_STATE_TTL);

        Map<String, StatefulTriggerService.Entry> state = StatefulTriggerService.readState(runContext, rStateKey, Optional.of(rStateTtl));
        StatefulTriggerService.Entry watermarkEntry = state.remove(WATERMARK_KEY);

        if (watermarkEntry == null) {
            Instant now = Instant.now();
            state.put(START_TIME_KEY, new StatefulTriggerService.Entry(START_TIME_KEY, now.toString(), now, now));
            writeState(runContext, rStateKey, state, now, rStateTtl);
            runContext.logger().info("First poll: Defender {} are read from {} on, existing items are not replayed", rResource, now);
            return Optional.empty();
        }

        Instant watermark = Instant.parse(watermarkEntry.version());
        Instant since = watermark.minus(rLookback);
        StatefulTriggerService.Entry startTime = state.get(START_TIME_KEY);
        if (startTime != null && since.isBefore(startTime.modifiedAt())) {
            // Look back across subsequent polls without replaying items from before the trigger was first enabled.
            since = startTime.modifiedAt();
        }
        String query = DefenderUtils.combineFilters(
            "lastUpdateDateTime ge " + ODATA_DATETIME.format(since),
            rFilter,
            DefenderUtils.eqClause("severity", rSeverity)
        );
        runContext.logger().debug("Polling Defender {} with filter: {}", rResource, query);

        GraphServiceClient client = graphClient(runContext);
        File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        Poll poll = new Poll(state, rOn, watermark);
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
            poll.output = output;
            // Graph lists most recent first and has no documented $orderby, so every page is read; fired items are written to the file page by page
            if (rResource == Resource.ALERTS) {
                readPages(
                    client.security().alertsV2().get(config -> config.queryParameters.filter = query),
                    AlertCollectionResponse::getValue,
                    AlertCollectionResponse::getOdataNextLink,
                    next -> client.security().alertsV2().withUrl(next).get(),
                    Alert::getId,
                    Alert::getLastUpdateDateTime,
                    poll
                );
            } else {
                readPages(
                    client.security().incidents().get(config -> config.queryParameters.filter = query),
                    IncidentCollectionResponse::getValue,
                    IncidentCollectionResponse::getOdataNextLink,
                    next -> client.security().incidents().withUrl(next).get(),
                    Incident::getId,
                    Incident::getLastUpdateDateTime,
                    poll
                );
            }
        } catch (ApiException e) {
            Files.deleteIfExists(tempFile.toPath());
            if (DefenderUtils.isTransient(e)) {
                // The state is left untouched so the next poll reads the same items again
                runContext.logger().warn("Microsoft Graph is throttling or unavailable (HTTP {}), skipping this poll", e.getResponseStatusCode());
                return Optional.empty();
            }
            throw DefenderUtils.graphError(e, "list " + rResource.name().toLowerCase());
        }

        if (poll.count == 0) {
            Files.deleteIfExists(tempFile.toPath());
            writeState(runContext, rStateKey, state, poll.watermark, rStateTtl);
            return Optional.empty();
        }

        URI uri = runContext.storage().putFile(tempFile);
        Output output = Output.builder()
            .uri(uri)
            .count(poll.count)
            .build();
        Execution execution = TriggerService.generateExecution(this, conditionContext, context, output);

        // Written last, once the file and the execution exist, so a failure before leaves the state as it was and the next poll retries the same items
        writeState(runContext, rStateKey, state, poll.watermark, rStateTtl);
        runContext.logger().info("{} new or updated Defender {}, latest lastUpdateDateTime {}", poll.count, rResource, poll.watermark);

        return Optional.of(execution);
    }

    private static <P, T extends Parsable> void readPages(P firstPage,
                                                          Function<P, List<T>> values,
                                                          Function<P, String> nextLink,
                                                          Function<String, P> fetchNext,
                                                          Function<T, String> id,
                                                          Function<T, OffsetDateTime> lastUpdateDateTime,
                                                          Poll poll) throws IOException {
        P page = firstPage;
        while (page != null) {
            List<T> pageValues = values.apply(page);
            if (pageValues != null) {
                for (T item : pageValues) {
                    poll.accept(item, id.apply(item), lastUpdateDateTime.apply(item));
                }
            }
            String next = nextLink.apply(page);
            page = next == null ? null : fetchNext.apply(next);
        }
    }

    private static void writeState(RunContext runContext, String key, Map<String, StatefulTriggerService.Entry> state, Instant watermark, Duration ttl) {
        StatefulTriggerService.Entry startTime = state.get(START_TIME_KEY);
        if (startTime != null) {
            state.put(START_TIME_KEY, new StatefulTriggerService.Entry(START_TIME_KEY, startTime.version(), startTime.modifiedAt(), Instant.now()));
        }
        state.put(WATERMARK_KEY, new StatefulTriggerService.Entry(WATERMARK_KEY, watermark.toString(), watermark, Instant.now()));
        StatefulTriggerService.writeState(runContext, key, state, Optional.of(ttl));
    }

    /**
     * Items read during one poll: updates the state, writes the items to fire to the output file and tracks the latest lastUpdateDateTime.
     */
    private static class Poll {
        private final Map<String, StatefulTriggerService.Entry> state;
        private final On on;
        private OutputStream output;
        private Instant watermark;
        private int count;

        Poll(Map<String, StatefulTriggerService.Entry> state, On on, Instant watermark) {
            this.state = state;
            this.on = on;
            this.watermark = watermark;
        }

        void accept(Parsable item, String id, OffsetDateTime lastUpdateDateTime) throws IOException {
            if (id == null || lastUpdateDateTime == null) {
                return;
            }
            Instant lastUpdate = lastUpdateDateTime.toInstant();
            if (lastUpdate.isAfter(watermark)) {
                watermark = lastUpdate;
            }
            StatefulTriggerService.Entry candidate = StatefulTriggerService.Entry.candidate(id, lastUpdate.toString(), lastUpdate);
            if (StatefulTriggerService.computeAndUpdateState(state, candidate, on).fire()) {
                FileSerde.write(output, DefenderUtils.toMap(item));
                count++;
            }
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "URI of the ION file with the new or updated alerts or incidents", description = "Objects with the Microsoft Graph field names, one per line.")
        private final URI uri;

        @Schema(title = "Number of items")
        private final int count;
    }
}
