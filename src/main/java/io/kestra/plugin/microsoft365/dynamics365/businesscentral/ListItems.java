package io.kestra.plugin.microsoft365.dynamics365.businesscentral;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

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
            title = "List items in Business Central",
            full = true,
            code = """
                id: bc_list_items
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.microsoft365.dynamics365.businesscentral.ListItems
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    environment: production
                    companyId: "00000000-0000-0000-0000-000000000001"
                    filter: "type eq 'Inventory'"
                    top: 50
                """
        )
    },
    metrics = {
        @Metric(
            name = "count",
            type = Counter.TYPE,
            unit = "items",
            description = "Number of items returned"
        )
    }
)
@Schema(
    title = "List Business Central items",
    description = """
        Retrieves items (products/services) from a Business Central company.
        Supports optional `$filter` and `$top` OData query parameters.
        Requires the `Financials.ReadWrite.All` API permission on the service principal.
        """
)
public class ListItems extends AbstractBusinessCentralTask implements RunnableTask<ListItems.Output> {

    @Schema(
        title = "Company ID",
        description = "GUID of the Business Central company."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> companyId;

    @Schema(
        title = "OData $filter expression",
        description = "Optional OData filter, e.g. `type eq 'Inventory'`."
    )
    @PluginProperty(group = "processing")
    private Property<String> filter;

    @Schema(
        title = "Maximum records to return ($top)",
        description = "Maximum number of item records to return. Defaults to 100."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> top = Property.ofValue(100);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rCompanyId = runContext.render(companyId).as(String.class).orElseThrow();
        var rFilter = runContext.render(filter).as(String.class).orElse(null);
        var rTop = runContext.render(top).as(Integer.class).orElse(100);

        var token = getAccessToken(runContext, scope(runContext));
        var urlBuilder = new StringBuilder(baseUrl(runContext))
            .append("/companies(").append(rCompanyId).append(")/items")
            .append("?$top=").append(rTop);

        if (rFilter != null) {
            urlBuilder.append("&$filter=").append(URLEncoder.encode(rFilter, StandardCharsets.UTF_8));
        }

        List<Map<String, Object>> items;

        try (var client = new HttpClient(runContext, httpConfiguration())) {
            var request = HttpRequest.builder()
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Accept", "application/json")
                .uri(URI.create(urlBuilder.toString()))
                .method("GET")
                .build();

            String body;
            try {
                var response = client.request(request, String.class);
                body = response.getBody() != null ? response.getBody() : "";
            } catch (HttpClientResponseException e) {
                throw parseAndThrowError(e.getResponse().getStatus().getCode(), responseBodyAsString(e));
            }

            var page = MAPPER.readValue(body, BcListResponse.class);
            items = page.getValue();
        }

        runContext.metric(Counter.of("count", items.size()));
        logger.info("Retrieved {} item(s) from company {}", items.size(), rCompanyId);

        return Output.builder()
            .items(items)
            .size(items.size())
            .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @lombok.Data
    static class BcListResponse {
        @com.fasterxml.jackson.annotation.JsonProperty("value")
        private List<Map<String, Object>> value = new ArrayList<>();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Items",
            description = "List of item records returned by Business Central."
        )
        private final List<Map<String, Object>> items;

        @Schema(
            title = "Total number of items",
            description = "Count of items returned."
        )
        private final Integer size;
    }
}
