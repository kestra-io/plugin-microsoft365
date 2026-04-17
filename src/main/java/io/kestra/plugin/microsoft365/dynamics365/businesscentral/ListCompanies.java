package io.kestra.plugin.microsoft365.dynamics365.businesscentral;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URI;
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
            title = "List all Business Central companies",
            full = true,
            code = """
                id: bc_list_companies
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.microsoft365.dynamics365.businesscentral.ListCompanies
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    environment: production
                """
        )
    },
    metrics = {
        @Metric(
            name = "count",
            type = Counter.TYPE,
            unit = "companies",
            description = "Number of companies returned"
        )
    }
)
@Schema(
    title = "List Business Central companies",
    description = """
        Retrieves all companies available in the Business Central environment.
        Requires the `Financials.ReadWrite.All` API permission on the service principal.
        """
)
public class ListCompanies extends AbstractBusinessCentralTask implements RunnableTask<ListCompanies.Output> {

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var token = getAccessToken(runContext, scope(runContext));
        var url = baseUrl(runContext) + "/companies";

        List<Map<String, Object>> companies;

        try (var client = new HttpClient(runContext, httpConfiguration())) {
            var request = HttpRequest.builder()
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Accept", "application/json")
                .uri(URI.create(url))
                .method("GET")
                .build();

            String body;
            try {
                var response = client.request(request, String.class);
                body = response.getBody() != null ? response.getBody() : "";
            } catch (HttpClientResponseException e) {
                parseAndThrowError(e.getResponse().getStatus().getCode(), responseBodyAsString(e));
                throw new IllegalStateException("unreachable");
            }

            var page = MAPPER.readValue(body, BcListResponse.class);
            companies = page.getValue();
        }

        runContext.metric(Counter.of("count", companies.size()));
        logger.info("Retrieved {} Business Central company(ies)", companies.size());

        return Output.builder()
            .companies(companies)
            .size(companies.size())
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
            title = "Companies",
            description = "List of company objects returned by Business Central."
        )
        private final List<Map<String, Object>> companies;

        @Schema(
            title = "Total number of companies",
            description = "Count of companies returned."
        )
        private final Integer size;
    }
}
