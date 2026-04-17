package io.kestra.plugin.microsoft365.dynamics365.dataverse;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URI;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Delete a contact record from Dataverse",
            full = true,
            code = """
                id: dataverse_delete_contact
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.microsoft365.dynamics365.dataverse.Delete
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    orgUrl: "https://myorg.api.crm.dynamics.com"
                    entitySetName: "contacts"
                    recordId: "00000000-0000-0000-0000-000000000001"
                """
        )
    }
)
@Schema(
    title = "Delete a Dataverse entity record",
    description = """
        Issues an OData DELETE request to permanently remove a record identified by its GUID.
        Requires the Dataverse application permission `Dynamics CRM user` on the service principal.
        """
)
public class Delete extends AbstractDataverseTask implements RunnableTask<VoidOutput> {

    @Schema(
        title = "Entity set name",
        description = "OData entity set name, e.g. `accounts`, `contacts`, `leads`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> entitySetName;

    @Schema(
        title = "Record ID",
        description = "GUID of the record to delete, e.g. `00000000-0000-0000-0000-000000000001`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> recordId;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rEntitySetName = runContext.render(entitySetName).as(String.class).orElseThrow();
        var rRecordId = runContext.render(recordId).as(String.class).orElseThrow();

        var rScope = scope(runContext);
        var token = getAccessToken(runContext, rScope);
        var url = baseUrl(runContext) + rEntitySetName + "(" + rRecordId + ")";

        try (var client = new HttpClient(runContext, httpConfiguration())) {
            var request = HttpRequest.builder()
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("OData-MaxVersion", "4.0")
                .addHeader("OData-Version", "4.0")
                .uri(URI.create(url))
                .method("DELETE")
                .build();

            try {
                client.request(request, String.class);
            } catch (HttpClientResponseException e) {
                parseAndThrowODataError(e.getResponse().getStatus().getCode(), responseBodyAsString(e));
                throw new IllegalStateException("unreachable");
            }
        }

        logger.info("Deleted Dataverse record {}/({}) successfully", rEntitySetName, rRecordId);
        return null;
    }
}
