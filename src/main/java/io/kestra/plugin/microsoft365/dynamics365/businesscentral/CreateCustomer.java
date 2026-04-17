package io.kestra.plugin.microsoft365.dynamics365.businesscentral;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
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
            title = "Create a customer in Business Central",
            full = true,
            code = """
                id: bc_create_customer
                namespace: company.team

                tasks:
                  - id: create
                    type: io.kestra.plugin.microsoft365.dynamics365.businesscentral.CreateCustomer
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    environment: production
                    companyId: "00000000-0000-0000-0000-000000000001"
                    customer:
                      displayName: "Acme Corp"
                      email: "billing@acme.com"
                      phoneNumber: "+1-555-0100"
                """
        )
    }
)
@Schema(
    title = "Create a Business Central customer",
    description = """
        Creates a new customer record in Business Central under the specified company.
        Requires the `Financials.ReadWrite.All` API permission on the service principal.
        """
)
public class CreateCustomer extends AbstractBusinessCentralTask implements RunnableTask<CreateCustomer.Output> {

    @Schema(
        title = "Company ID",
        description = "GUID of the Business Central company."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> companyId;

    @Schema(
        title = "Customer fields",
        description = "Map of field names to values for the new customer record."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> customer;

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rCompanyId = runContext.render(companyId).as(String.class).orElseThrow();
        var rCustomer = runContext.render(customer).asMap(String.class, Object.class);

        var token = getAccessToken(runContext, scope());
        var url = baseUrl(runContext) + "/companies(" + rCompanyId + ")/customers";
        var body = MAPPER.writeValueAsString(rCustomer);

        Map<String, Object> created;

        try (var client = new HttpClient(runContext, httpConfiguration())) {
            var request = HttpRequest.builder()
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .uri(URI.create(url))
                .method("POST")
                .body(HttpRequest.StringRequestBody.builder().content(body).build())
                .build();

            var response = client.request(request, String.class);
            var statusCode = response.getStatus().getCode();
            var responseBody = response.getBody() != null ? response.getBody() : "";

            if (statusCode < 200 || statusCode >= 300) {
                ListCompanies.parseAndThrowError(statusCode, responseBody);
            }

            created = MAPPER.readValue(responseBody, new TypeReference<>() {});
        }

        var customerId = (String) created.get("id");
        logger.info("Created customer {} in company {}", customerId, rCompanyId);

        return Output.builder()
            .customerId(customerId)
            .customer(created)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Customer ID",
            description = "GUID of the newly created customer."
        )
        private final String customerId;

        @Schema(
            title = "Customer record",
            description = "Full customer record as returned by Business Central after creation."
        )
        private final Map<String, Object> customer;
    }
}
