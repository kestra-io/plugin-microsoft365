package io.kestra.plugin.microsoft365;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.UsernamePasswordCredential;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.microsoft365.teams.adaptivecards.Send;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.lang.reflect.Method;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;

@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class AbstractGraphConnectionTest {

    @Inject
    private RunContextFactory runContextFactory;

    // `credentials` is intentionally private: it is an implementation detail of AbstractGraphConnection, not part of
    // the public task contract. Reflection lets the test assert branch selection without exposing it.
    private static TokenCredential invokeCredentials(AbstractGraphConnection task, RunContext runContext) throws Exception {
        Method credentialsMethod = AbstractGraphConnection.class.getDeclaredMethod("credentials", RunContext.class);
        credentialsMethod.setAccessible(true);
        return (TokenCredential) credentialsMethod.invoke(task, runContext);
    }

    @Test
    void usernamePasswordSelectsDelegatedCredential() throws Exception {
        RunContext runContext = runContextFactory.of();

        var task = Send.builder()
            .tenantId(Property.ofValue("mock-tenant-id"))
            .clientId(Property.ofValue("mock-client-id"))
            .username(Property.ofValue("user@example.com"))
            .password(Property.ofValue("super-secret-password"))
            .teamId(Property.ofValue("team-1"))
            .channelId(Property.ofValue("channel-1"))
            .card(Property.ofValue("{}"))
            .build();

        TokenCredential credential = invokeCredentials(task, runContext);

        assertThat(credential, instanceOf(UsernamePasswordCredential.class));
    }

    @Test
    void clientSecretIsStillSelectedWhenNoDelegatedCredentialsAreSet() throws Exception {
        RunContext runContext = runContextFactory.of();

        var task = Send.builder()
            .tenantId(Property.ofValue("mock-tenant-id"))
            .clientId(Property.ofValue("mock-client-id"))
            .clientSecret(Property.ofValue("mock-client-secret"))
            .teamId(Property.ofValue("team-1"))
            .channelId(Property.ofValue("channel-1"))
            .card(Property.ofValue("{}"))
            .build();

        TokenCredential credential = invokeCredentials(task, runContext);

        assertThat(credential, instanceOf(ClientSecretCredential.class));
    }
}
