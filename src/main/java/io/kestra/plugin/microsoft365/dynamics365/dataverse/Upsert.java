package io.kestra.plugin.microsoft365.dynamics365.dataverse;

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
            title = "Upsert an account record in Dataverse",
            full = true,
            code = """
                id: dataverse_upsert_account
                namespace: company.team

                tasks:
                  - id: upsert
                    type: io.kestra.plugin.microsoft365.dynamics365.dataverse.Upsert
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    orgUrl: "https://myorg.api.crm.dynamics.com"
                    entitySetName: "accounts"
                    recordId: "00000000-0000-0000-0000-000000000001"
                    record:
                      name: "Contoso Ltd"
                      emailaddress1: "info@contoso.com"
                """
        )
    }
)
@Schema(
    title = "Upsert a Dataverse entity record",
    description = """
        Issues an OData PATCH request to create or update a record identified by its GUID.
        If the record exists it is updated; if it does not exist it is created (upsert semantics).
        Requires the Dataverse application permission `Dynamics CRM user` on the service principal.
        """
)
public class Upsert extends AbstractDataverseTask implements RunnableTask<Upsert.Output> {

    @Schema(
        title = "Entity set name",
        description = "OData entity set name, e.g. `accounts`, `contacts`, `leads`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> entitySetName;

    @Schema(
        title = "Record ID",
        description = "GUID of the record to create or update, e.g. `00000000-0000-0000-0000-000000000001`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> recordId;

    @Schema(
        title = "Record fields",
        description = "Map of field names to values to set on the record."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> record;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rEntitySetName = runContext.render(entitySetName).as(String.class).orElseThrow();
        var rRecordId = runContext.render(recordId).as(String.class).orElseThrow();
        var rRecord = runContext.render(record).asMap(String.class, Object.class);

        var rScope = scope(runContext);
        var token = getAccessToken(runContext, rScope);
        var url = baseUrl(runContext) + rEntitySetName + "(" + rRecordId + ")";

        var body = MAPPER.writeValueAsString(rRecord);

        try (var client = new HttpClient(runContext, httpConfiguration())) {
            var request = HttpRequest.builder()
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("OData-MaxVersion", ODATA_VERSION)
                .addHeader("OData-Version", ODATA_VERSION)
                .addHeader("If-Match", "*")
                .uri(URI.create(url))
                .method("PATCH")
                .body(HttpRequest.StringRequestBody.builder().content(body).build())
                .build();

            try {
                client.request(request, String.class);
            } catch (HttpClientResponseException e) {
                throw parseAndThrowError(e.getResponse().getStatus().getCode(), responseBodyAsString(e));
            }
        }

        logger.info("Upserted Dataverse record {}/({}) successfully", rEntitySetName, rRecordId);

        return Output.builder()
            .recordId(rRecordId)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Record ID",
            description = "GUID of the created or updated record."
        )
        private final String recordId;
    }
}
