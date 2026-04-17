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
            title = "Create a sales invoice in Business Central",
            full = true,
            code = """
                id: bc_create_invoice
                namespace: company.team

                tasks:
                  - id: create
                    type: io.kestra.plugin.microsoft365.dynamics365.businesscentral.CreateInvoice
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    environment: production
                    companyId: "00000000-0000-0000-0000-000000000001"
                    invoice:
                      customerNumber: "C00010"
                      invoiceDate: "2026-04-17"
                      dueDate: "2026-05-17"
                """
        )
    }
)
@Schema(
    title = "Create a Business Central sales invoice",
    description = """
        Creates a new sales invoice in Business Central under the specified company.
        Requires the `Financials.ReadWrite.All` API permission on the service principal.
        """
)
public class CreateInvoice extends AbstractBusinessCentralTask implements RunnableTask<CreateInvoice.Output> {

    @Schema(
        title = "Company ID",
        description = "GUID of the Business Central company."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> companyId;

    @Schema(
        title = "Invoice fields",
        description = "Map of field names to values for the new sales invoice."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> invoice;

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rCompanyId = runContext.render(companyId).as(String.class).orElseThrow();
        var rInvoice = runContext.render(invoice).asMap(String.class, Object.class);

        var token = getAccessToken(runContext, scope());
        var url = baseUrl(runContext) + "/companies(" + rCompanyId + ")/salesInvoices";
        var body = MAPPER.writeValueAsString(rInvoice);

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

        var invoiceId = (String) created.get("id");
        logger.info("Created sales invoice {} in company {}", invoiceId, rCompanyId);

        return Output.builder()
            .invoiceId(invoiceId)
            .invoice(created)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Invoice ID",
            description = "GUID of the newly created sales invoice."
        )
        private final String invoiceId;

        @Schema(
            title = "Invoice record",
            description = "Full sales invoice record as returned by Business Central after creation."
        )
        private final Map<String, Object> invoice;
    }
}
