package io.kestra.plugin.microsoft365.oneshare;


import com.microsoft.graph.drives.item.items.item.delta.DeltaGetResponse;
import com.microsoft.graph.drives.item.items.item.delta.DeltaRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
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

import io.kestra.plugin.microsoft365.AbstractMicrosoft365Trigger;
import io.kestra.plugin.microsoft365.oneshare.models.OneShareFile;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger on file changes in OneDrive/SharePoint",
    description = "Monitors a folder for file changes (create, update, or both) using Microsoft Graph Delta API"
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Monitor OneDrive folder for new files",
            code = """
                id: file_created_trigger
                namespace: company.team

                tasks:
                  - id: log_new_file
                    type: io.kestra.core.tasks.log.Log
                    message: "New file detected: {{ trigger.files[0].name }}"

                triggers:
                  - id: file_created
                    type: io.kestra.plugin.microsoft365.oneshare.Trigger
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    path: "/Documents"
                    interval: PT1M
                """
        ),
        @Example(
            full = true,
            title = "Monitor OneDrive folder for file updates",
            code = """
                id: file_updated_trigger
                namespace: company.team

                tasks:
                  - id: log_updated_file
                    type: io.kestra.core.tasks.log.Log
                    message: "File updated: {{ trigger.files[0].name }}"

                triggers:
                  - id: file_updated
                    type: io.kestra.plugin.microsoft365.oneshare.Trigger
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    path: "/Documents"
                    interval: PT1M
                    on: UPDATE
                """
        ),
        @Example(
            full = true,
            title = "Monitor OneDrive folder for any file changes",
            code = """
                id: file_changes_trigger
                namespace: company.team

                tasks:
                  - id: log_file_changes
                    type: io.kestra.core.tasks.log.Log
                    message: "File changed: {{ trigger.files[0].name }}"

                triggers:
                  - id: file_changes
                    type: io.kestra.plugin.microsoft365.oneshare.Trigger
                    tenantId: "{{ secret('TENANT_ID') }}"
                    clientId: "{{ secret('CLIENT_ID') }}"
                    clientSecret: "{{ secret('CLIENT_SECRET') }}"
                    driveId: "b!abc123def456"
                    path: "/Documents"
                    interval: PT1M
                    on: CREATE_OR_UPDATE
                """
        )
    }
)
public class Trigger extends AbstractMicrosoft365Trigger implements PollingTriggerInterface, StatefulTriggerInterface, TriggerOutput<Trigger.Output> {

    private static final String DELTA_LINK_KEY = "__graphDeltaLink";

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
        title = "Trigger event type",
        description = """
            Defines when the trigger fires.
            - `CREATE`: only for newly discovered files (default).
            - `UPDATE`: only when an already-seen file is modified (ETag or size changes fallback).
            - `CREATE_OR_UPDATE`: fires on either event.
            """
    )
    @Builder.Default
    protected Property<StatefulTriggerInterface.On> on = Property.of(StatefulTriggerInterface.On.CREATE);

    @Schema(
        title = "State key",
        description = """
            JSON-type KV key for persisted state.
            Default: `<namespace>__<flowId>__<triggerId>`
            """
    )
    protected Property<String> stateKey;

    @Schema(
        title = "State TTL",
        description = "TTL for persisted state entries (e.g., PT24H, P7D)."
    )
    protected Property<Duration> stateTtl;

    @Override
    public Duration getInterval() {
        return this.interval;
    }

    @Override
    public Property<StatefulTriggerInterface.On> getOn() {
        return this.on;
    }

    @Override
    public Property<String> getStateKey() {
        return this.stateKey;
    }

    @Override
    public Property<Duration> getStateTtl() {
        return this.stateTtl;
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
            runContext.logger().warn("Both driveId ({}) and siteId ({}) provided - driveId takes precedence", rDriveId, rSiteId);
            // Explicitly null out siteId to make it clear driveId is used
            rSiteId = null;
        }

        GraphServiceClient graphClient = graphClient(runContext);

        // Use StatefulTriggerService for state management
        String rStateKey = runContext.render(stateKey).as(String.class).orElse(
            StatefulTriggerService.defaultKey(context.getNamespace(), context.getFlowId(), this.id)
        );
        Duration rStateTtl = runContext.render(stateTtl).as(Duration.class).orElse(Duration.ofDays(7));
        StatefulTriggerInterface.On rOn = runContext.render(on).as(StatefulTriggerInterface.On.class).orElse(StatefulTriggerInterface.On.CREATE);

        // Read current state
        var state = StatefulTriggerService.readState(runContext, rStateKey, Optional.ofNullable(rStateTtl));
        
        // Check if this is the first run (empty state except possibly delta link)
        boolean isFirstRun = state.isEmpty() || state.size() == 1 && state.containsKey(DELTA_LINK_KEY);

        try {
            // Get the stored delta link from previous run (stored as a special entry)
            String storedDeltaLink = null;
            var deltaEntry = state.get(DELTA_LINK_KEY);
            if (deltaEntry != null) {
                storedDeltaLink = deltaEntry.version(); // Store delta link in version field
            }
            
            // Execute delta query with stored delta link for efficiency
            if (storedDeltaLink != null) {
                runContext.logger().debug("Using stored delta link for incremental sync");
            } else {
                runContext.logger().debug("No delta link found - performing full sync");
            }
            var deltaResponse = executeDeltaQuery(graphClient, rDriveId, rSiteId, rPath, storedDeltaLink);
            List<DriveItem> allItems = new ArrayList<>();
            if (deltaResponse.getValue() != null) {
                allItems.addAll(deltaResponse.getValue());
            }

            // Paginate through all results and capture final delta link
            String nextLink = deltaResponse.getOdataNextLink();
            String newDeltaLink = deltaResponse.getOdataDeltaLink();
            while (nextLink != null) {
                var page = fetchDeltaByLink(graphClient, nextLink);
                if (page.getValue() != null) {
                    allItems.addAll(page.getValue());
                }
                newDeltaLink = page.getOdataDeltaLink(); // Update with latest delta link
                nextLink = page.getOdataNextLink();
            }

            // Process files and check state
            List<OneShareFile> filesToTrigger = new ArrayList<>();
            for (DriveItem item : allItems) {
                if (item.getFile() != null && item.getDeleted() == null && item.getId() != null) {
                    // Create a state entry for this file
                    String fileUri = item.getId();
                    String fileVersion = item.getETag() != null ? item.getETag() : String.valueOf(item.getSize());
                    var modifiedAt = item.getLastModifiedDateTime() != null ?
                        item.getLastModifiedDateTime().toInstant() :
                        item.getCreatedDateTime().toInstant();

                    var candidate = StatefulTriggerService.Entry.candidate(fileUri, fileVersion, modifiedAt);
                    var stateUpdate = StatefulTriggerService.computeAndUpdateState(state, candidate, rOn);

                    // On first run, optionally don't trigger to avoid flooding with existing files
                    // But still update state to track them
                    if (stateUpdate.fire() && !isFirstRun) {
                        filesToTrigger.add(OneShareFile.of(item));
                    } else if (isFirstRun && stateUpdate.isNew()) {
                        // First run: just mark files as seen without triggering
                        runContext.logger().debug("First run - marking existing file as seen: {}", item.getName());
                    }
                }
            }

            // Store the delta link for next run (if we have one)
            if (newDeltaLink != null) {
                var deltaLinkEntry = new StatefulTriggerService.Entry(DELTA_LINK_KEY, newDeltaLink, 
                    Instant.now(), Instant.now());
                state.put(DELTA_LINK_KEY, deltaLinkEntry);
            }

            // Save updated state
            StatefulTriggerService.writeState(runContext, rStateKey, state, Optional.ofNullable(rStateTtl));

            runContext.logger().debug("Processed {} total items, {} files triggered execution", allItems.size(), filesToTrigger.size());

            if (filesToTrigger.isEmpty()) {
                return Optional.empty();
            }

            var output = Output.builder()
                .files(filesToTrigger)
                .count(filesToTrigger.size())
                .build();

            runContext.logger().info("Triggering execution with {} new/updated files", filesToTrigger.size());
            return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
        } catch (ApiException e) {
            if (e.getResponseStatusCode() == 410) {
                runContext.logger().warn("Delta link expired, clearing stored delta link and will start fresh next time");
                // Remove the expired delta link from state
                state.remove(DELTA_LINK_KEY);
                StatefulTriggerService.writeState(runContext, rStateKey, state, Optional.ofNullable(rStateTtl));
                return Optional.empty();
            }
            throw e;
        }
    }

    protected DeltaGetResponse executeDeltaQuery(
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

    protected DeltaGetResponse fetchDeltaByLink(GraphServiceClient graphClient, String nextLink) throws Exception {
        DeltaRequestBuilder builder = new DeltaRequestBuilder(nextLink, graphClient.getRequestAdapter());
        return builder.get();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "List of files that triggered the execution")
        private List<OneShareFile> files;

        @Schema(title = "Number of files that triggered the execution")
        private int count;
    }
}
