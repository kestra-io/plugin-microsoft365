package io.kestra.plugin.microsoft365.dynamics365.businesscentral;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Get a customer by ID from Business Central",
            full = true,
            code = """
                id: bc_get_customer
                namespace: company.team

                tasks:
                  - id: get
                    type: io.kestra.plugin.microsoft365.dynamics365.businesscentral.GetCustomer
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    environment: production
                    companyId: "00000000-0000-0000-0000-000000000001"
                    customerId: "00000000-0000-0000-0000-000000000002"
                """
        )
    }
)
@Schema(
    title = "Get a Business Central customer",
    description = """
        Retrieves a single customer record from Business Central by company ID and customer ID.
        Requires the `Financials.ReadWrite.All` API permission on the service principal.
        """
)
public class GetCustomer extends AbstractBusinessCentralTask implements RunnableTask<GetCustomer.Output> {

    @Schema(
        title = "Company ID",
        description = "GUID of the Business Central company."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> companyId;

    @Schema(
        title = "Customer ID",
        description = "GUID of the customer to retrieve."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> customerId;

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rCompanyId = runContext.render(companyId).as(String.class).orElseThrow();
        var rCustomerId = runContext.render(customerId).as(String.class).orElseThrow();

        var token = getAccessToken(runContext, scope(runContext));
        var url = baseUrl(runContext) + "/companies(" + rCompanyId + ")/customers(" + rCustomerId + ")";

        Map<String, Object> customer;

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

            customer = MAPPER.readValue(body, new TypeReference<>() {});
        }

        logger.info("Retrieved customer {} from company {}", rCustomerId, rCompanyId);

        return Output.builder()
            .customer(customer)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Customer record",
            description = "Customer fields returned by Business Central."
        )
        private final Map<String, Object> customer;
    }
}
