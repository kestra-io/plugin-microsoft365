package io.kestra.plugin.microsoft365;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ClientCertificateCredentialBuilder;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.runners.RunContext;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractMicrosoft365Trigger extends AbstractTrigger implements MicrosoftGraphConnectionInterface {
    protected Property<String> tenantId;
    protected Property<String> clientId;
    protected Property<String> clientSecret;
    protected Property<String> pemCertificate;

    protected GraphServiceClient graphClient(RunContext runContext) throws IllegalVariableEvaluationException {
        TokenCredential credential = this.credentials(runContext);
        return new GraphServiceClient(credential);
    }

    private TokenCredential credentials(RunContext runContext) throws IllegalVariableEvaluationException {
        final String tenantId = runContext.render(this.tenantId).as(String.class).orElse(null);
        final String clientId = runContext.render(this.clientId).as(String.class).orElse(null);
        final String clientSecret = runContext.render(this.clientSecret).as(String.class).orElse(null);
        final String pemCertificate = runContext.render(this.pemCertificate).as(String.class).orElse(null);

        // Option 1: Client Secret authentication (recommended for most scenarios)
        if(StringUtils.isNotBlank(clientSecret)) {
            runContext.logger().info("Authentication is using Client Secret Credentials");
            return new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .clientSecret(clientSecret)
                .build();
        }

        // Option 2: Client Certificate authentication (alternative for enhanced security)
        if(StringUtils.isNotBlank(pemCertificate)) {
            runContext.logger().info("Authentication is using Client Certificate Credentials");
            return new ClientCertificateCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .pemCertificate(new ByteArrayInputStream(StandardCharsets.UTF_8.encode(pemCertificate).array()))
                .build();
        }

        // Fallback: Default Azure Credential (for managed identities)
        runContext.logger().info("Authentication is using Default Azure Credentials");
        return new DefaultAzureCredentialBuilder().tenantId(tenantId).build();
    }
}
