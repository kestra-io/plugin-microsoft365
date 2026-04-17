package io.kestra.plugin.microsoft365.dynamics365.dataverse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Flux;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Query active accounts from Dataverse",
            full = true,
            code = """
                id: dataverse_query_accounts
                namespace: company.team

                tasks:
                  - id: query
                    type: io.kestra.plugin.microsoft365.dynamics365.dataverse.Query
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    orgUrl: "https://myorg.api.crm.dynamics.com"
                    entitySetName: "accounts"
                    filter: "statecode eq 0"
                    select: "accountid,name,emailaddress1"
                    top: 50
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "Store all contacts to internal storage",
            full = true,
            code = """
                id: dataverse_store_contacts
                namespace: company.team

                tasks:
                  - id: store
                    type: io.kestra.plugin.microsoft365.dynamics365.dataverse.Query
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    orgUrl: "https://myorg.api.crm.dynamics.com"
                    entitySetName: "contacts"
                    select: "contactid,fullname,emailaddress1"
                    fetchType: STORE
                """
        )
    },
    metrics = {
        @Metric(
            name = "count",
            type = Counter.TYPE,
            unit = "records",
            description = "Total number of records returned by the query"
        )
    }
)
@Schema(
    title = "Query Dataverse entities via OData",
    description = """
        Executes an OData GET request against a Dataverse entity set.
        Supports `$filter`, `$select`, and `$top` query parameters.
        When `fetchType` is `FETCH` or `STORE`, follows `@odata.nextLink` pagination to retrieve all pages.
        When `fetchType` is `FETCH_ONE`, returns only the first record from the first page.
        Requires the Dataverse application permission `Dynamics CRM user` on the service principal.
        """
)
public class Query extends AbstractDataverseTask implements RunnableTask<Query.Output> {

    @Schema(
        title = "Entity set name",
        description = "OData entity set name, e.g. `accounts`, `contacts`, `leads`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> entitySetName;

    @Schema(
        title = "OData $filter expression",
        description = "Optional OData filter, e.g. `statecode eq 0`."
    )
    @PluginProperty(group = "processing")
    private Property<String> filter;

    @Schema(
        title = "OData $select fields",
        description = "Comma-separated list of fields to return, e.g. `accountid,name`."
    )
    @PluginProperty(group = "processing")
    private Property<String> select;

    @Schema(
        title = "Maximum records per page ($top)",
        description = """
            Maximum number of records to return per OData page.
            When `fetchType` is `STORE` or `FETCH`, all pages are followed regardless of this value.
            When `fetchType` is `FETCH_ONE`, this is ignored and only the first record is returned.
            Defaults to 100.
            """
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> top = Property.ofValue(100);

    @Schema(
        title = "Fetch type",
        description = """
            FETCH_ONE — returns only the first record from the first page.
            FETCH — returns all records (following pagination) as a list in the task output.
            STORE — writes all records (following pagination) to Kestra internal storage as an Ion file.
            """
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rEntitySetName = runContext.render(entitySetName).as(String.class).orElseThrow();
        var rFilter = runContext.render(filter).as(String.class).orElse(null);
        var rSelect = runContext.render(select).as(String.class).orElse(null);
        var rTop = runContext.render(top).as(Integer.class).orElse(100);
        var rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        var rBaseUrl = baseUrl(runContext);
        var rScope = scope(runContext);
        var token = getAccessToken(runContext, rScope);

        var urlBuilder = new StringBuilder(rBaseUrl).append(rEntitySetName);
        if (rFetchType == FetchType.FETCH_ONE) {
            urlBuilder.append("?$top=1");
        } else {
            urlBuilder.append("?$top=").append(rTop);
        }
        if (rFilter != null) {
            urlBuilder.append("&$filter=").append(URLEncoder.encode(rFilter, StandardCharsets.UTF_8));
        }
        if (rSelect != null) {
            urlBuilder.append("&$select=").append(URLEncoder.encode(rSelect, StandardCharsets.UTF_8));
        }

        List<Map<String, Object>> allRecords = new ArrayList<>();
        String nextUrl = urlBuilder.toString();

        try (var client = new HttpClient(runContext, httpConfiguration())) {
            while (nextUrl != null) {
                var request = HttpRequest.builder()
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("Accept", "application/json")
                    .addHeader("OData-MaxVersion", "4.0")
                    .addHeader("OData-Version", "4.0")
                    .uri(URI.create(nextUrl))
                    .method("GET")
                    .build();

                HttpResponse<String> response;
                try {
                    response = client.request(request, String.class);
                } catch (HttpClientResponseException e) {
                    parseAndThrowODataError(e.getResponse().getStatus().getCode(), responseBodyAsString(e));
                    throw new IllegalStateException("unreachable");
                }
                var body = response.getBody() != null ? response.getBody() : "";

                var page = MAPPER.readValue(body, ODataResponse.class);
                allRecords.addAll(page.getValue());

                if (rFetchType == FetchType.FETCH_ONE) {
                    break;
                }

                nextUrl = page.getOdataNextLink();
                if (nextUrl != null) {
                    logger.debug("Following OData nextLink, total so far: {}", allRecords.size());
                }
            }
        }

        runContext.metric(Counter.of("count", allRecords.size()));
        logger.info("Dataverse query on '{}' returned {} record(s)", rEntitySetName, allRecords.size());

        if (rFetchType == FetchType.FETCH_ONE) {
            var record = allRecords.isEmpty() ? null : allRecords.getFirst();
            return Output.builder()
                .records(record == null ? List.of() : List.of(record))
                .size(record == null ? 0 : 1)
                .build();
        }

        if (rFetchType == FetchType.STORE) {
            var tempFile = storeRecords(runContext, allRecords);
            return Output.builder()
                .uri(runContext.storage().putFile(tempFile))
                .size(allRecords.size())
                .build();
        }

        return Output.builder()
            .records(allRecords)
            .size(allRecords.size())
            .build();
    }

    private File storeRecords(RunContext runContext, List<Map<String, Object>> records) throws Exception {
        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        try (var writer = new BufferedWriter(new FileWriter(tempFile), FileSerde.BUFFER_SIZE)) {
            FileSerde.writeAll(writer, Flux.fromIterable(records)).block();
        }
        return tempFile;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @lombok.Data
    static class ODataResponse {
        @com.fasterxml.jackson.annotation.JsonProperty("value")
        private List<Map<String, Object>> value = new ArrayList<>();

        @com.fasterxml.jackson.annotation.JsonProperty("@odata.nextLink")
        private String odataNextLink;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Records",
            description = "List of entity records returned; populated when fetchType is FETCH or FETCH_ONE."
        )
        private final List<Map<String, Object>> records;

        @Schema(
            title = "URI of stored records file",
            description = "URI of the Ion file containing all records; populated when fetchType is STORE."
        )
        private final URI uri;

        @Schema(
            title = "Total number of records",
            description = "Count of records returned (or stored) by the query."
        )
        private final Integer size;
    }
}
