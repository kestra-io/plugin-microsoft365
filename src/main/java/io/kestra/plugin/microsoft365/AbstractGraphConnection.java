package io.kestra.plugin.microsoft365;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ClientCertificateCredentialBuilder;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.UsernamePasswordCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
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
public abstract class AbstractGraphConnection extends Task implements MicrosoftGraphConnectionInterface {
    protected Property<String> tenantId;
    protected Property<String> clientId;
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> clientSecret;
    @PluginProperty(secret = true, group = "advanced")
    @ToString.Exclude
    protected Property<String> pemCertificate;

    @Schema(
        title = "Username",
        description = """
            Username of the delegated user to authenticate as (Resource Owner Password Credentials flow).
            Set this together with password for delegated (acting-as-user) authentication instead of app-only \
            clientSecret/pemCertificate credentials. This is required for operations Microsoft Graph rejects under \
            app-only auth, such as sending Teams channel or chat messages.
            Requires the Azure AD app registration to have "Allow public client flows" enabled and the relevant \
            delegated Graph permission granted (e.g. ChannelMessage.Send or Chat.ReadWrite).
            """
    )
    @PluginProperty(group = "connection")
    protected Property<String> username;

    @Schema(
        title = "Password",
        description = """
            Password of the delegated user, used together with username for Resource Owner Password Credentials \
            (ROPC) authentication. Requires the Azure AD app registration to have "Allow public client flows" \
            enabled and the relevant delegated Graph permission granted (e.g. ChannelMessage.Send or Chat.ReadWrite).
            """
    )
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> password;

    public GraphServiceClient graphClient(RunContext runContext) throws IllegalVariableEvaluationException {
        TokenCredential credential = this.credentials(runContext);
        return new GraphServiceClient(credential);
    }

    private TokenCredential credentials(RunContext runContext) throws IllegalVariableEvaluationException {
        final String tenantId = runContext.render(this.tenantId).as(String.class).orElse(null);
        final String clientId = runContext.render(this.clientId).as(String.class).orElse(null);

        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(clientId)) {
            throw new IllegalArgumentException("tenantId and clientId are required for authentication");
        }

        // Option 1: Username/Password (ROPC, delegated) authentication. Required for operations Microsoft Graph
        // rejects under app-only credentials, e.g. sending Teams channel/chat messages.
        final String username = runContext.render(this.username).as(String.class).orElse(null);
        final String password = runContext.render(this.password).as(String.class).orElse(null);
        if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
            runContext.logger().info("Authentication is using Username/Password (delegated) Credentials");
            return new UsernamePasswordCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .username(username)
                .password(password)
                .build();
        }

        // Option 2: Client Secret authentication (recommended for most scenarios)
        final String clientSecret = runContext.render(this.clientSecret).as(String.class).orElse(null);
        if(StringUtils.isNotBlank(clientSecret)) {
            runContext.logger().info("Authentication is using Client Secret Credentials");
            return new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .clientSecret(clientSecret)
                .build();
        }

        // Option 3: Client Certificate authentication (alternative for enhanced security)
        final String pemCertificate = runContext.render(this.pemCertificate).as(String.class).orElse(null);
        if(StringUtils.isNotBlank(pemCertificate)) {
            runContext.logger().info("Authentication is using Client Certificate Credentials");
            return new ClientCertificateCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .pemCertificate(new ByteArrayInputStream(pemCertificate.getBytes(StandardCharsets.UTF_8)))
                .build();
        }

        // Fallback: Default Azure Credential (for managed identities)
        runContext.logger().info("Authentication is using Default Azure Credentials");
        return new DefaultAzureCredentialBuilder().tenantId(tenantId).build();
    }
}
