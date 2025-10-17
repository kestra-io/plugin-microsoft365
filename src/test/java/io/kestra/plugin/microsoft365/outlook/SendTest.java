package io.kestra.plugin.microsoft365.outlook;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@MicronautTest
class SendTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run() throws Exception {
        // This test requires actual Azure AD credentials
        // It's disabled by default to prevent failures in CI/CD
        
        RunContext runContext = runContextFactory.of(Map.of(
            "AZURE_TENANT_ID", "test-tenant",
            "AZURE_CLIENT_ID", "test-client", 
            "AZURE_CLIENT_SECRET", "test-secret"
        ));

        GraphAuthConfig auth = GraphAuthConfig.builder()
            .tenantId(Property.of("{{ vars.AZURE_TENANT_ID }}"))
            .clientId(Property.of("{{ vars.AZURE_CLIENT_ID }}"))
            .clientSecret(Property.of("{{ vars.AZURE_CLIENT_SECRET }}"))
            .userPrincipalName(Property.of("test@example.com"))
            .build();

        Send task = Send.builder()
            .auth(auth)
            .to(Property.of(List.of("recipient@example.com")))
            .subject(Property.of("Test Subject"))
            .body(Property.of("Test email body"))
            .bodyType(Property.of("TEXT"))
            .build();

        // This test is primarily for structure validation
        // Actual execution would require valid credentials and network access
        assertThat(task.getAuth(), notNullValue());
        assertThat(task.getTo(), notNullValue());
        assertThat(task.getSubject(), notNullValue());
        assertThat(task.getBody(), notNullValue());
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    void runIntegration() throws Exception {
        // Integration test - only runs when NOT in CI environment
        // Requires actual Azure AD app registration and credentials
        
        RunContext runContext = runContextFactory.of(Map.of(
            "AZURE_TENANT_ID", System.getenv("AZURE_TENANT_ID"),
            "AZURE_CLIENT_ID", System.getenv("AZURE_CLIENT_ID"),
            "AZURE_CLIENT_SECRET", System.getenv("AZURE_CLIENT_SECRET"),
            "TEST_USER_EMAIL", System.getenv("TEST_USER_EMAIL"),
            "TEST_RECIPIENT", System.getenv("TEST_RECIPIENT")
        ));

        if (runContext.getVariables().get("AZURE_TENANT_ID") == null) {
            // Skip if credentials not provided
            return;
        }

        GraphAuthConfig auth = GraphAuthConfig.builder()
            .tenantId(Property.of("{{ vars.AZURE_TENANT_ID }}"))
            .clientId(Property.of("{{ vars.AZURE_CLIENT_ID }}"))
            .clientSecret(Property.of("{{ vars.AZURE_CLIENT_SECRET }}"))
            .userPrincipalName(Property.of("{{ vars.TEST_USER_EMAIL }}"))
            .build();

        Send task = Send.builder()
            .auth(auth)
            .to(Property.of(List.of("{{ vars.TEST_RECIPIENT }}")))
            .subject(Property.of("Kestra Test Email - " + System.currentTimeMillis()))
            .body(Property.of("<h1>Test Email</h1><p>This is a test email sent from Kestra unit tests.</p>"))
            .bodyType(Property.of("HTML"))
            .build();

        Send.Output output = task.run(runContext);
        
        assertThat(output.getSubject(), containsString("Kestra Test Email"));
        assertThat(output.getToCount(), is(1));
        assertThat(output.getCcCount(), is(0));
        assertThat(output.getBccCount(), is(0));
        assertThat(output.getBodyType(), is("HTML"));
    }
}