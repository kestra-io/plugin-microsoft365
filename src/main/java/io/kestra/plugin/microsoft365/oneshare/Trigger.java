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

        // Validate inputs
        if (rDriveId == null && rSiteId == null) {
            throw new IllegalArgumentException("Either driveId or siteId must be provided");
        }

        if (rDriveId != null && rSiteId != null) {
            runContext.logger().warn("Both driveId ({}) and siteId ({}) provided - driveId takes precedence", rDriveId, rSiteId);
            // Explicitly null out siteId to make it clear driveId is used
            rSiteId = null;
        }

        // Validate path
        if (rPath == null || rPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Path cannot be empty");
        }

        if (!rPath.startsWith("/")) {
            throw new IllegalArgumentException(
                String.format("Path must start with '/'. Provided path: '%s'", rPath));
        }

        GraphServiceClient graphClient;
        try {
            graphClient = graphClient(runContext);
        } catch (Exception e) {
            runContext.logger().error("Failed to create Graph API client: {}", e.getMessage(), e);
            throw new IllegalStateException(
                "Failed to authenticate with Microsoft Graph API. Please verify your credentials (tenantId, clientId, clientSecret)", e);
        }

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
            
            DeltaGetResponse deltaResponse;
            try {
                deltaResponse = executeDeltaQuery(graphClient, rDriveId, rSiteId, rPath, storedDeltaLink);
            } catch (ApiException e) {
                if (e.getResponseStatusCode() == 404) {
                    runContext.logger().error("Folder not found at path '{}'. Please verify the path exists in your OneDrive/SharePoint. " +
                        "Common paths: '/' (root), '/Documents', '/Shared Documents'", rPath);
                    throw new IllegalArgumentException(
                        String.format("Folder not found: %s. Please ensure the path exists in your drive", rPath), e);
                } else if (e.getResponseStatusCode() == 410) {
                    runContext.logger().warn("Delta link expired, clearing stored delta link and will start fresh next time");
                    // Remove the expired delta link from state
                    state.remove(DELTA_LINK_KEY);
                    StatefulTriggerService.writeState(runContext, rStateKey, state, Optional.ofNullable(rStateTtl));
                    return Optional.empty();
                } else if (e.getResponseStatusCode() == 401) {
                    throw new IllegalStateException(
                        "Authentication failed. Please verify your credentials (tenantId, clientId, clientSecret)", e);
                } else if (e.getResponseStatusCode() == 403) {
                    throw new IllegalStateException(
                        String.format("Permission denied. Insufficient permissions to monitor path '%s'", rPath), e);
                } else if (e.getResponseStatusCode() == 429) {
                    runContext.logger().warn("Rate limit exceeded. Skipping this polling cycle");
                    return Optional.empty();
                } else if (e.getResponseStatusCode() == 503 || e.getResponseStatusCode() == 504) {
                    runContext.logger().warn("Microsoft Graph API temporarily unavailable. Skipping this polling cycle");
                    return Optional.empty();
                }
                
                throw new RuntimeException(
                    String.format("Failed to query delta for path '%s': %s", rPath, e.getMessage()), e);
            }
            
            if (deltaResponse == null) {
                throw new IllegalStateException(
                    String.format("Failed to query delta for path '%s': No response received from Microsoft Graph API", rPath));
            }
            
            List<DriveItem> allItems = new ArrayList<>();
            if (deltaResponse.getValue() != null) {
                allItems.addAll(deltaResponse.getValue());
            }

            // Paginate through all results and capture final delta link
            String nextLink = deltaResponse.getOdataNextLink();
            String newDeltaLink = deltaResponse.getOdataDeltaLink();
            int pageCount = 1;
            
            while (nextLink != null) {
                runContext.logger().debug("Fetching delta page {}", pageCount + 1);
                
                DeltaGetResponse page;
                try {
                    page = fetchDeltaByLink(graphClient, nextLink);
                } catch (ApiException e) {
                    if (e.getResponseStatusCode() == 410) {
                        runContext.logger().warn("Delta link expired during pagination at page {}. Clearing delta link", pageCount + 1);
                        state.remove(DELTA_LINK_KEY);
                        StatefulTriggerService.writeState(runContext, rStateKey, state, Optional.ofNullable(rStateTtl));
                        return Optional.empty();
                    } else if (e.getResponseStatusCode() == 429) {
                        runContext.logger().warn("Rate limit exceeded during pagination at page {}. Returning partial results", pageCount + 1);
                        break;
                    } else if (e.getResponseStatusCode() == 503 || e.getResponseStatusCode() == 504) {
                        runContext.logger().warn("API unavailable during pagination at page {}. Returning partial results", pageCount + 1);
                        break;
                    }
                    
                    runContext.logger().error("Error fetching delta page {}: {}. Returning partial results", 
                        pageCount + 1, e.getMessage());
                    break;
                } catch (Exception e) {
                    runContext.logger().error("Unexpected error during delta pagination at page {}: {}. Returning partial results", 
                        pageCount + 1, e.getMessage());
                    break;
                }
                
                if (page != null) {
                    if (page.getValue() != null) {
                        allItems.addAll(page.getValue());
                    }
                    newDeltaLink = page.getOdataDeltaLink(); // Update with latest delta link
                    nextLink = page.getOdataNextLink();
                    pageCount++;
                } else {
                    break;
                }
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
                        item.getCreatedDateTime() != null ? item.getCreatedDateTime().toInstant() : Instant.now();

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
            try {
                StatefulTriggerService.writeState(runContext, rStateKey, state, Optional.ofNullable(rStateTtl));
            } catch (Exception e) {
                runContext.logger().error("Failed to save trigger state: {}", e.getMessage(), e);
                // Don't throw - we can continue even if state save fails
            }

            runContext.logger().debug("Processed {} total items across {} pages, {} files triggered execution", 
                allItems.size(), pageCount, filesToTrigger.size());

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
            // Handle any uncaught ApiException
            runContext.logger().error("Microsoft Graph API error in trigger: {}", e.getMessage(), e);
            
            if (e.getResponseStatusCode() == 401) {
                throw new IllegalStateException(
                    "Authentication failed. Please verify your credentials (tenantId, clientId, clientSecret)", e);
            } else if (e.getResponseStatusCode() == 429 || e.getResponseStatusCode() == 503 || e.getResponseStatusCode() == 504) {
                // For rate limiting or service unavailability, just skip this cycle
                runContext.logger().warn("Skipping trigger cycle due to API error: {}", e.getMessage());
                return Optional.empty();
            }
            
            throw new RuntimeException(
                String.format("Failed to evaluate trigger for path '%s': %s", rPath, e.getMessage()), e);
                
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Re-throw our custom exceptions
            throw e;
        } catch (Exception e) {
            runContext.logger().error("Unexpected error in trigger: {}", e.getMessage(), e);
            throw new RuntimeException(
                String.format("Unexpected error evaluating trigger for path '%s': %s", rPath, e.getMessage()), e);
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
        try {
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
                
                if (drive == null || drive.getId() == null) {
                    throw new IllegalStateException(
                        String.format("Failed to retrieve drive for site '%s'", siteId));
                }
                
                String siteDriveId = drive.getId();

                return graphClient.drives()
                    .byDriveId(siteDriveId)
                    .items()
                    .byDriveItemId("root:" + path + ":")
                    .delta()
                    .get();
            }
        } catch (ApiException e) {
            if (e.getResponseStatusCode() == 404) {
                throw new IllegalArgumentException(
                    String.format("Path '%s' not found. Please verify the path exists", path), e);
            } else if (e.getResponseStatusCode() == 403) {
                throw new IllegalStateException(
                    String.format("Permission denied. Insufficient permissions to access path '%s'", path), e);
            } else if (e.getResponseStatusCode() == 400) {
                throw new IllegalArgumentException(
                    String.format("Invalid path '%s'. Please ensure the path is correctly formatted", path), e);
            }
            throw e;
        }
    }

    protected DeltaGetResponse fetchDeltaByLink(GraphServiceClient graphClient, String nextLink) throws Exception {
        if (nextLink == null || nextLink.trim().isEmpty()) {
            throw new IllegalArgumentException("Next link cannot be empty");
        }
        
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
