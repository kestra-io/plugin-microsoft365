package io.kestra.plugin.microsoft365.dynamics365.businesscentral;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
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
            title = "Get a sales invoice from Business Central",
            full = true,
            code = """
                id: bc_get_invoice
                namespace: company.team

                tasks:
                  - id: get
                    type: io.kestra.plugin.microsoft365.dynamics365.businesscentral.GetInvoice
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    environment: production
                    companyId: "00000000-0000-0000-0000-000000000001"
                    invoiceId: "00000000-0000-0000-0000-000000000003"
                """
        )
    }
)
@Schema(
    title = "Get a Business Central sales invoice",
    description = """
        Retrieves a single sales invoice from Business Central by company ID and invoice ID.
        Requires the `Financials.ReadWrite.All` API permission on the service principal.
        """
)
public class GetInvoice extends AbstractBusinessCentralTask implements RunnableTask<GetInvoice.Output> {

    @Schema(
        title = "Company ID",
        description = "GUID of the Business Central company."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> companyId;

    @Schema(
        title = "Invoice ID",
        description = "GUID of the sales invoice to retrieve."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> invoiceId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rCompanyId = runContext.render(companyId).as(String.class).orElseThrow();
        var rInvoiceId = runContext.render(invoiceId).as(String.class).orElseThrow();

        var token = getAccessToken(runContext, scope(runContext));
        var url = baseUrl(runContext) + "/companies(" + rCompanyId + ")/salesInvoices(" + rInvoiceId + ")";

        Map<String, Object> invoice;

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
                throw parseAndThrowError(e.getResponse().getStatus().getCode(), responseBodyAsString(e));
            }

            invoice = MAPPER.readValue(body, new TypeReference<>() {});
        }

        logger.info("Retrieved invoice {} from company {}", rInvoiceId, rCompanyId);

        return Output.builder()
            .invoice(invoice)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Invoice record",
            description = "Sales invoice fields returned by Business Central."
        )
        private final Map<String, Object> invoice;
    }
}
