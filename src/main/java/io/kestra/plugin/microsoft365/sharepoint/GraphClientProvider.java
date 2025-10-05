package io.kestra.plugin.microsoft365.sharepoint;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
import com.microsoft.graph.requests.GraphServiceClient;
import java.util.Collections;

public final class GraphClientProvider {
    private static GraphServiceClient<?> client;

    public static GraphServiceClient<?> getClient() throws SharePointException {
        if (client == null) {
            try {
                // Validate required environment variables
                String clientId = System.getenv("AZURE_CLIENT_ID");
                String clientSecret = System.getenv("AZURE_CLIENT_SECRET");
                String tenantId = System.getenv("AZURE_TENANT_ID");
                
                if (clientId == null || clientId.isEmpty()) {
                    throw new SharePointException("AZURE_CLIENT_ID environment variable is not set or is empty");
                }
                if (clientSecret == null || clientSecret.isEmpty()) {
                    throw new SharePointException("AZURE_CLIENT_SECRET environment variable is not set or is empty");
                }
                if (tenantId == null || tenantId.isEmpty()) {
                    throw new SharePointException("AZURE_TENANT_ID environment variable is not set or is empty");
                }
                
                ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .tenantId(tenantId)
                    .build();

                TokenCredentialAuthProvider authProvider = new TokenCredentialAuthProvider(
                    Collections.singletonList("https://graph.microsoft.com/.default"),
                    credential
                );

                client = GraphServiceClient.builder()
                    .authenticationProvider(authProvider)
                    .buildClient();
            } catch (Exception e) {
                if (e instanceof SharePointException) {
                    throw e;
                } else {
                    throw new SharePointException("Failed to initialize Microsoft Graph client: " + e.getMessage(), e);
                }
            }
        }
        return client;
    }
}
