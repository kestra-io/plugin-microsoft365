package io.kestra.plugin.microsoft365;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.UsernamePasswordCredentialBuilder;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.commons.lang3.StringUtils;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractGraphDelegatedConnection extends AbstractGraphConnection {
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

    @Override
    protected TokenCredential credentials(RunContext runContext) throws IllegalVariableEvaluationException {
        final String username = runContext.render(this.username).as(String.class).orElse(null);
        final String password = runContext.render(this.password).as(String.class).orElse(null);

        if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
            final String tenantId = runContext.render(this.tenantId).as(String.class).orElse(null);
            final String clientId = runContext.render(this.clientId).as(String.class).orElse(null);

            if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(clientId)) {
                throw new IllegalArgumentException("tenantId and clientId are required for authentication");
            }

            runContext.logger().info("Authentication is using Username/Password (delegated) Credentials");
            return new UsernamePasswordCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .username(username)
                .password(password)
                .build();
        }

        return super.credentials(runContext);
    }
}
