package io.kestra.plugin.microsoft365.outlook;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.runners.RunContext;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import lombok.experimental.UtilityClass;

@UtilityClass
public class GraphService {

    public static GraphServiceClient createClientCredentialClient(GraphAuthConfig config, RunContext runContext)
            throws IllegalVariableEvaluationException {

        String tenantId = runContext.render(config.getTenantId()).as(String.class).orElseThrow();
        String clientId = runContext.render(config.getClientId()).as(String.class).orElseThrow();
        String clientSecret = runContext.render(config.getClientSecret()).as(String.class).orElseThrow();

        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
            .clientId(clientId)
            .clientSecret(clientSecret)
            .tenantId(tenantId)
            .build();

        return new GraphServiceClient(credential);
    }
}
