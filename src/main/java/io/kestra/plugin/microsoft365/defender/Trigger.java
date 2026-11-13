package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.models.security.Alert;
import com.microsoft.graph.models.security.AlertCollectionResponse;
import com.microsoft.graph.models.security.Incident;
import com.microsoft.graph.models.security.IncidentCollectionResponse;
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
    description = """
        Polls `/security/alerts_v2` or `/security/incidents` for items whose `lastUpdateDateTime` (`createdDateTime`
        with `on: CREATE`) is at or after the last one seen minus `lookback`, and starts one execution with the items
        not fired yet, stored as an ION file in internal storage. Seen items are kept in the trigger state by id and
        `lastUpdateDateTime`, so items sharing a timestamp or indexed late are not missed and are not fired twice. On
        the first poll no execution is started, so existing items are not replayed. Requires the Microsoft Graph
        application permission SecurityAlert.Read.All or SecurityIncident.Read.All.
        """
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
                    severity: HIGH

                tasks:
                  - id: each_incident
                    type: io.kestra.plugin.core.flow.ForEachItem
                    items: "{{ trigger.uri }}"
                    batch:
                      rows: 1
                    namespace: company.security
                    flowId: triage_incident
                    inputs:
                      incident_file: "{{ taskrun.items }}"
                """
        ),
        @Example(
            title = "Start a flow on new Defender for Endpoint alerts only",
            full = true,
            code = """
                id: defender_on_endpoint_alert
                namespace: company.security

                triggers:
                  - id: on_alert
                    type: io.kestra.plugin.microsoft365.defender.Trigger
                    interval: PT5M
                    on: CREATE
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    filter: "serviceSource eq 'microsoftDefenderForEndpoint'"

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.count }} new alerts stored in {{ trigger.uri }}"
                """
        )
    }
)
public class Trigger extends AbstractMicrosoft365Trigger implements PollingTriggerInterface, StatefulTriggerInterface, TriggerOutput<Trigger.Output> {
    // OData datetime literal with the 7 fractional digits used by Microsoft Graph
    private static final DateTimeFormatter ODATA_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'").withZone(ZoneOffset.UTC);
    // State entries holding the latest polled timestamp seen, next to the entries of the seen items
    static final String WATERMARK_KEY = "__lastUpdateDateTime";
    static final String CREATED_WATERMARK_KEY = "__createdDateTime";
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

    @Schema(title = "Severity", description = "Only fire for items with this severity. Allowed values: `INFORMATIONAL`, `LOW`, `MEDIUM`, `HIGH`.")
    @PluginProperty(group = "processing")
    private Property<Severity> severity;

    @Schema(
        title = "Lookback",
        description = """
            How far before the latest timestamp seen each poll reads again, to catch items indexed late. Default `PT5M`.
            Must be zero or positive and shorter than `stateTtl`. Items already fired are skipped using the trigger state.
            """
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Duration> lookback = Property.ofValue(DEFAULT_LOOKBACK);

    @Schema(title = "Polling interval", description = "ISO-8601 duration between two polls. Default `PT1M`.")
    @Builder.Default
    @PluginProperty(group = "execution")
    private Duration interval = Duration.ofMinutes(1);

    @Schema(
        title = "Trigger event type",
        description = """
            `CREATE` fires for items not seen before and polls on `createdDateTime`, so an item forgotten after `stateTtl`
            does not fire again when it changes. `UPDATE` fires for seen items with a new `lastUpdateDateTime`.
            `CREATE_OR_UPDATE` (default) fires for both.
            """
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<On> on = Property.ofValue(On.CREATE_OR_UPDATE);

    @Schema(title = "State key", description = "Key of the trigger state in the namespace KV store; defaults to `<namespace>_<flowId>_<triggerId>`.")
    @PluginProperty(group = "advanced")
    private Property<String> stateKey;

    @Schema(
        title = "State TTL",
        description = """
            How long a seen item is remembered after it was last read. Default `P7D`. Must be positive and longer than
            `lookback`. Items still in the lookback window are kept and refreshed on every poll. An item that changes
            after it was forgotten is seen as new, so with `UPDATE` that change does not fire; `CREATE` and
            `CREATE_OR_UPDATE` are not affected.
            """
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Duration> stateTtl = Property.ofValue(DEFAULT_STATE_TTL);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();

        var rResource = runContext.render(resource).as(Resource.class).orElse(Resource.ALERTS);
        var rFilter = runContext.render(filter).as(String.class).orElse(null);
        var rSeverity = runContext.render(severity).as(Severity.class).orElse(null);
        var rLookback = runContext.render(lookback).as(Duration.class).orElse(DEFAULT_LOOKBACK);
        var rOn = runContext.render(on).as(On.class).orElse(On.CREATE_OR_UPDATE);
        var rStateKey = runContext.render(stateKey).as(String.class)
            .orElse(StatefulTriggerService.defaultKey(context.getNamespace(), context.getFlowId(), this.id));
        var rStateTtl = runContext.render(stateTtl).as(Duration.class).orElse(DEFAULT_STATE_TTL);

        if (rLookback.isNegative()) {
            throw new IllegalArgumentException("lookback (%s) must be zero or positive. Set lookback to PT0S or longer.".formatted(rLookback));
        }
        if (rStateTtl.isZero() || rStateTtl.isNegative()) {
            throw new IllegalArgumentException("stateTtl (%s) must be positive. Set stateTtl to a positive duration such as P7D.".formatted(rStateTtl));
        }
        if (rStateTtl.compareTo(rLookback) <= 0) {
            throw new IllegalArgumentException(
                "stateTtl (%s) must be longer than lookback (%s). Increase stateTtl or reduce lookback.".formatted(rStateTtl, rLookback));
        }

        // CREATE polls on the immutable createdDateTime, so an item forgotten after stateTtl is not read again when it changes
        var polledField = rOn == On.CREATE ? "createdDateTime" : "lastUpdateDateTime";
        var watermarkKey = rOn == On.CREATE ? CREATED_WATERMARK_KEY : WATERMARK_KEY;

        // Read without a TTL: expired entries still in the lookback window are kept until they are refreshed below
        var state = StatefulTriggerService.readState(runContext, rStateKey, Optional.empty());
        var watermarkEntry = state.remove(watermarkKey);
        state.remove(rOn == On.CREATE ? WATERMARK_KEY : CREATED_WATERMARK_KEY);

        if (watermarkEntry == null) {
            var now = Instant.now();
            state.put(START_TIME_KEY, new StatefulTriggerService.Entry(START_TIME_KEY, now.toString(), now, now));
            writeState(runContext, rStateKey, state, watermarkKey, now, rStateTtl);
            runContext.logger().info("First poll: Defender {} are read from {} on, existing items are not replayed", rResource, now);
            return Optional.empty();
        }

        var watermark = Instant.parse(watermarkEntry.version());
        var startTime = state.get(START_TIME_KEY);
        // Never look back before the first poll, so items that existed before the trigger was enabled are not replayed
        var since = startTime != null && watermark.minus(rLookback).isBefore(startTime.modifiedAt())
            ? startTime.modifiedAt()
            : watermark.minus(rLookback);
        var stateCutoff = Instant.now().minus(rStateTtl);
        state.entrySet().removeIf(entry -> !START_TIME_KEY.equals(entry.getKey())
            && entry.getValue().lastSeenAt() != null
            && !entry.getValue().lastSeenAt().isAfter(stateCutoff)
            && (entry.getValue().modifiedAt() == null || entry.getValue().modifiedAt().isBefore(since)));
        var query = DefenderUtils.combineFilters(
            "%s ge %s".formatted(polledField, ODATA_DATETIME.format(since)),
            rFilter,
            DefenderUtils.eqClause("severity", rSeverity)
        );
        runContext.logger().debug("Polling Defender {} with filter: {}", rResource, query);

        var client = graphClient(runContext);
        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        var poll = new Poll(state, rOn, watermark);
        try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
            poll.output = output;
            // No $orderby is documented, so every page is read before the watermark moves
            if (rResource == Resource.ALERTS) {
                readPages(
                    client.security().alertsV2().get(config -> config.queryParameters.filter = query),
                    AlertCollectionResponse::getValue,
                    AlertCollectionResponse::getOdataNextLink,
                    next -> client.security().alertsV2().withUrl(next).get(),
                    Alert::getId,
                    Alert::getCreatedDateTime,
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
                    Incident::getCreatedDateTime,
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
            throw DefenderUtils.graphError(e, "list %s".formatted(rResource.name().toLowerCase()));
        }

        if (poll.count == 0) {
            Files.deleteIfExists(tempFile.toPath());
            writeState(runContext, rStateKey, state, watermarkKey, poll.watermark, rStateTtl);
            return Optional.empty();
        }

        var uri = runContext.storage().putFile(tempFile);
        var output = Output.builder()
            .uri(uri)
            .count(poll.count)
            .build();
        var execution = TriggerService.generateExecution(this, conditionContext, context, output);

        // Written once the file and the execution exist, so a failure before leaves the state as it was and the next poll retries
        writeState(runContext, rStateKey, state, watermarkKey, poll.watermark, rStateTtl);
        runContext.logger().info("{} new or updated Defender {}, latest {} {}", poll.count, rResource, polledField, poll.watermark);

        return Optional.of(execution);
    }

    private static <P, T extends Parsable> void readPages(P firstPage,
                                                          Function<P, List<T>> values,
                                                          Function<P, String> nextLink,
                                                          Function<String, P> fetchNext,
                                                          Function<T, String> id,
                                                          Function<T, OffsetDateTime> createdDateTime,
                                                          Function<T, OffsetDateTime> lastUpdateDateTime,
                                                          Poll poll) throws IOException {
        var page = firstPage;
        while (page != null) {
            var pageValues = values.apply(page);
            if (pageValues != null) {
                for (var item : pageValues) {
                    poll.accept(item, id.apply(item), createdDateTime.apply(item), lastUpdateDateTime.apply(item));
                }
            }
            var next = nextLink.apply(page);
            page = next == null ? null : fetchNext.apply(next);
        }
    }

    private static void writeState(RunContext runContext, String key, Map<String, StatefulTriggerService.Entry> state,
                                   String watermarkKey, Instant watermark, Duration ttl) {
        var startTime = state.get(START_TIME_KEY);
        if (startTime != null) {
            state.put(START_TIME_KEY, new StatefulTriggerService.Entry(START_TIME_KEY, startTime.version(), startTime.modifiedAt(), Instant.now()));
        }
        state.put(watermarkKey, new StatefulTriggerService.Entry(watermarkKey, watermark.toString(), watermark, Instant.now()));
        StatefulTriggerService.writeState(runContext, key, state, Optional.of(ttl));
    }

    // Items read during one poll: updates the state, writes the items to fire to the output file and tracks the watermark
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

        void accept(Parsable item, String id, OffsetDateTime createdDateTime, OffsetDateTime lastUpdateDateTime) throws IOException {
            var polledDateTime = on == On.CREATE ? createdDateTime : lastUpdateDateTime;
            if (id == null || polledDateTime == null) {
                return;
            }
            var polledAt = polledDateTime.toInstant();
            if (polledAt.isAfter(watermark)) {
                watermark = polledAt;
            }
            var version = lastUpdateDateTime == null ? null : lastUpdateDateTime.toInstant().toString();
            var candidate = StatefulTriggerService.Entry.candidate(id, version, polledAt);
            var fire = StatefulTriggerService.computeAndUpdateState(state, candidate, on).fire();
            // Core keeps the first lastSeenAt of unchanged items, so it is refreshed here to keep the items of the lookback window
            state.put(id, new StatefulTriggerService.Entry(id, version, polledAt, Instant.now()));
            if (fire) {
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
