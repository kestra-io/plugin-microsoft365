package io.kestra.plugin.microsoft365.oneshare;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.drives.item.items.item.delta.DeltaRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.*;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.microsoft365.AbstractMicrosoft365Trigger;
import io.kestra.plugin.microsoft365.oneshare.models.OneShareFile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger when a new file is created in OneDrive/SharePoint",
    description = "Monitors a folder for newly created files using Microsoft Graph Delta API"
)
@Plugin(
    examples = {
        @Example(
            title = "Monitor OneDrive folder for new files",
            full = true,
            code = """
                id: file_created_trigger
                namespace: company.microsoft365

                tasks:
                  - id: log_new_file
                    type: io.kestra.core.tasks.log.Log
                    message: |
                      New file detected:
                      - Name: {{ trigger.name }}
                      - ID: {{ trigger.id }}
                      - URL: {{ trigger.webUrl }}
                      - Created: {{ trigger.createdDateTime }}

                triggers:
                  - id: file_created
                    type: io.kestra.plugin.microsoft365.oneshare.Trigger
                    tenantId: "{{ secret('MS365_TENANT_ID') }}"
                    clientId: "{{ secret('MS365_CLIENT_ID') }}"
                    clientSecret: "{{ secret('MS365_CLIENT_SECRET') }}"
                    driveId: "b!abc123"
                    path: "/Documents"
                    interval: PT1M
                """
        )
    }
)
public class Trigger extends AbstractMicrosoft365Trigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Schema(
        title = "Drive ID to monitor",
        description = "OneDrive or SharePoint drive identifier. Either driveId or siteId must be provided."
    )
    protected Property<String> driveId;

    @Schema(
        title = "Site ID to monitor",
        description = "SharePoint site identifier. Either driveId or siteId must be provided."
    )
    protected Property<String> siteId;

    @Schema(
        title = "Folder path to monitor",
        description = "Path to the folder to monitor for new files, e.g., /Documents"
    )
    @NotNull
    protected Property<String> path;

    @Schema(
        title = "Polling interval",
        description = "How frequently to check for new files"
    )
    @Builder.Default
    private Duration interval = Duration.ofMinutes(1);

    @Schema(
        title = "State key",
        description = "JSON-type KV key for persisted state. Default: <namespace>__<flowId>__<triggerId>"
    )
    protected Property<String> stateKey;

    @Schema(
        title = "State TTL",
        description = "TTL for persisted state (e.g., PT24H, P7D)."
    )
    protected Property<Duration> stateTtl;

    @Override
    public Duration getInterval() {
        return this.interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();

        // Render and validate configuration
        String rDriveId = runContext.render(this.driveId).as(String.class).orElse(null);
        String rSiteId = runContext.render(this.siteId).as(String.class).orElse(null);
        String rPath = runContext.render(this.path).as(String.class).orElseThrow();

        if (rDriveId == null && rSiteId == null) {
            throw new IllegalArgumentException("Either driveId or siteId must be provided");
        }

        if (rDriveId != null && rSiteId != null) {
            runContext.logger().warn("Both driveId and siteId provided — using driveId");
        }

        GraphServiceClient graphClient = graphClient(runContext);

        // Resolve state context
        var rStateKey = runContext.render(stateKey).as(String.class).orElse(defaultStateKey(context));
        var rStateTtl = runContext.render(stateTtl).as(Duration.class).orElse(Duration.ofDays(7));
        var stateContext = new StateContext(true, "m365_file_created_state", id, rStateKey, java.util.Optional.ofNullable(rStateTtl));

        // Read state
        var state = readState(runContext, stateContext);
        String deltaLink = state.deltaLink;
        OffsetDateTime lastRun = state.lastRun != null ? state.lastRun : OffsetDateTime.now(ZoneOffset.UTC).minus(this.interval);

        try {
            // Execute delta and paginate
            var first = executeDeltaQuery(graphClient, rDriveId, rSiteId, rPath, deltaLink);
            java.util.List<DriveItem> allItems = new ArrayList<>();
            if (first.getValue() != null) {
                allItems.addAll(first.getValue());
            }
            String nextLink = first.getOdataNextLink();
            String newDeltaLink = first.getOdataDeltaLink();
            while (nextLink != null) {
                var page = fetchDeltaByLink(graphClient, nextLink);
                if (page.getValue() != null) {
                    allItems.addAll(page.getValue());
                }
                newDeltaLink = page.getOdataDeltaLink();
                nextLink = page.getOdataNextLink();
            }

            // Determine all newly created files since last run
            java.util.List<DriveItem> newFiles = allItems.stream()
                .filter(item -> item.getFile() != null)
                .filter(item -> item.getDeleted() == null)
                .filter(item -> item.getCreatedDateTime() != null && item.getCreatedDateTime().isAfter(lastRun.minusSeconds(5)))
                .sorted(Comparator.comparing(DriveItem::getCreatedDateTime))
                .toList();

            // Persist new state - use newDeltaLink if available, otherwise keep the previous deltaLink
            String deltaLinkToSave = newDeltaLink != null ? newDeltaLink : deltaLink;
            writeState(runContext, stateContext, new StateEntry(deltaLinkToSave, OffsetDateTime.now(ZoneOffset.UTC)));

            if (newFiles.isEmpty()) {
                return Optional.empty();
            }

            var files = newFiles.stream().map(OneShareFile::of).toList();
            var output = Output.builder()
                .files(files)
                .count(files.size())
                .build();

            return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
        } catch (ApiException e) {
            if (e.getResponseStatusCode() == 410) {
                runContext.logger().warn("Delta link expired, resetting state");
                writeState(runContext, stateContext, new StateEntry(null, OffsetDateTime.now(ZoneOffset.UTC)));
                return Optional.empty();
            }
            throw e;
        }
    }

    protected com.microsoft.graph.drives.item.items.item.delta.DeltaGetResponse executeDeltaQuery(
        GraphServiceClient graphClient,
        String driveId,
        String siteId,
        String path,
        String deltaLink
    ) throws Exception {

        // If we have a delta link from previous call, use it directly
        if (deltaLink != null) {
            // Use the request builder directly from the delta link without requiring a driveId
            DeltaRequestBuilder builder = new DeltaRequestBuilder(deltaLink, graphClient.getRequestAdapter());
            return builder.get();
        }

        // Initial delta query for specific folder path
        // Endpoint format: /drives/{drive-id}/root:/{path}:/delta
        if (driveId != null) {
            return graphClient.drives()
                .byDriveId(driveId)
                .items()
                .byDriveItemId("root:" + path + ":")
                .delta()
                .get();
        } else {
            // For site-based access
            var drive = graphClient.sites().bySiteId(siteId).drive().get();
            String siteDriveId = drive.getId();

            return graphClient.drives()
                .byDriveId(siteDriveId)
                .items()
                .byDriveItemId("root:" + path + ":")
                .delta()
                .get();
        }
    }

    protected com.microsoft.graph.drives.item.items.item.delta.DeltaGetResponse fetchDeltaByLink(GraphServiceClient graphClient, String nextLink) throws Exception {
        DeltaRequestBuilder builder = new DeltaRequestBuilder(nextLink, graphClient.getRequestAdapter());
        return builder.get();
    }

    private String defaultStateKey(TriggerContext triggerContext) {
        return triggerContext.getNamespace() + "__" + triggerContext.getFlowId() + "__" + id;
    }

    protected Trigger.StateEntry readState(RunContext runContext, StateContext stateContext) {
        var flowInfo = runContext.flowInfo();
        try {
            var kvOpt = runContext.namespaceKv(flowInfo.namespace()).getValue(stateContext.taskRunValue());
            if (kvOpt.isEmpty()) {
                return new StateEntry(null, null);
            }
            var entry = MAPPER.readValue((byte[]) kvOpt.get().value(), new TypeReference<StateEntry>() {});
            return entry != null ? entry : new StateEntry(null, null);
        } catch (Exception e) {
            runContext.logger().warn("Unable to read state: {}", e.toString());
            return new StateEntry(null, null);
        }
    }

    protected void writeState(RunContext runContext, StateContext stateContext, StateEntry state) {
        try {
            var bytes = MAPPER.writeValueAsBytes(state);
            var flowInfo = runContext.flowInfo();
            KVMetadata metadata = new KVMetadata("M365 FileCreated Trigger State", stateContext.ttl().orElse(null));
            runContext.namespaceKv(flowInfo.namespace()).put(stateContext.taskRunValue(), new KVValueAndMetadata(metadata, bytes));
        } catch (Exception e) {
            runContext.logger().error("Unable to write state: {}", e.toString());
        }
    }

    record StateContext(boolean flowScoped, String stateName, String stateSubName, String taskRunValue, Optional<Duration> ttl) { }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StateEntry {
        private String deltaLink;
        private OffsetDateTime lastRun;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "List of newly created files")
        private java.util.List<OneShareFile> files;

        @Schema(title = "Number of new files")
        private int count;
    }
}
