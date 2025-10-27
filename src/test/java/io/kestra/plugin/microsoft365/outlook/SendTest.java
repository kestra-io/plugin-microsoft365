package io.kestra.plugin.microsoft365.outlook;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.users.UsersRequestBuilder;
import com.microsoft.graph.users.item.UserItemRequestBuilder;
import com.microsoft.graph.users.item.sendmail.SendMailPostRequestBody;
import com.microsoft.graph.users.item.sendmail.SendMailRequestBuilder;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@KestraTest
class SendTest {

    @Inject
    private RunContextFactory runContextFactory;

    // ---- Mock handles ----
    private static MockedConstruction<GraphServiceClient> graphClientMock;
    private static MockedConstruction<UsersRequestBuilder> usersRequestBuilderMock;
    private static MockedConstruction<UserItemRequestBuilder> userItemRequestBuilderMock;
    private static MockedConstruction<SendMailRequestBuilder> sendMailRequestBuilderMock;

    // ---- Mock constants ----
    private static final String MOCK_TENANT_ID = "mock-tenant-id";
    private static final String MOCK_CLIENT_ID = "mock-client-id";
    private static final String MOCK_CLIENT_SECRET = "mock-client-secret";
    private static final String MOCK_FROM_EMAIL = "sender@example.com";

    @BeforeAll
    static void setupMocks() {
        // Mock SendMailRequestBuilder - the final call in the chain
        sendMailRequestBuilderMock = Mockito.mockConstruction(SendMailRequestBuilder.class, (mock, context) -> {
            doNothing().when(mock).post(any(SendMailPostRequestBody.class));
        });

        // Mock UserItemRequestBuilder - returns SendMailRequestBuilder
        userItemRequestBuilderMock = Mockito.mockConstruction(UserItemRequestBuilder.class, (mock, context) -> {
            SendMailRequestBuilder sendMailBuilder = Mockito.mock(SendMailRequestBuilder.class);
            doNothing().when(sendMailBuilder).post(any(SendMailPostRequestBody.class));
            when(mock.sendMail()).thenReturn(sendMailBuilder);
        });

        // Mock UsersRequestBuilder - returns UserItemRequestBuilder
        usersRequestBuilderMock = Mockito.mockConstruction(UsersRequestBuilder.class, (mock, context) -> {
            UserItemRequestBuilder userItemBuilder = Mockito.mock(UserItemRequestBuilder.class);
            SendMailRequestBuilder sendMailBuilder = Mockito.mock(SendMailRequestBuilder.class);
            doNothing().when(sendMailBuilder).post(any(SendMailPostRequestBody.class));
            when(userItemBuilder.sendMail()).thenReturn(sendMailBuilder);
            when(mock.byUserId(any(String.class))).thenReturn(userItemBuilder);
        });

        // Mock GraphServiceClient - the root of the chain
        graphClientMock = Mockito.mockConstruction(GraphServiceClient.class, (mock, context) -> {
            UsersRequestBuilder usersBuilder = Mockito.mock(UsersRequestBuilder.class);
            UserItemRequestBuilder userItemBuilder = Mockito.mock(UserItemRequestBuilder.class);
            SendMailRequestBuilder sendMailBuilder = Mockito.mock(SendMailRequestBuilder.class);

            doNothing().when(sendMailBuilder).post(any(SendMailPostRequestBody.class));
            when(userItemBuilder.sendMail()).thenReturn(sendMailBuilder);
            when(usersBuilder.byUserId(any(String.class))).thenReturn(userItemBuilder);
            when(mock.users()).thenReturn(usersBuilder);
        });

        System.out.println("✅ Mocked GraphServiceClient and its request builder chain");
    }

    @AfterAll
    static void tearDownMocks() {
        if (sendMailRequestBuilderMock != null) {
            sendMailRequestBuilderMock.close();
            System.out.println("🧹 Mock for SendMailRequestBuilder released");
        }
        if (userItemRequestBuilderMock != null) {
            userItemRequestBuilderMock.close();
            System.out.println("🧹 Mock for UserItemRequestBuilder released");
        }
        if (usersRequestBuilderMock != null) {
            usersRequestBuilderMock.close();
            System.out.println("🧹 Mock for UsersRequestBuilder released");
        }
        if (graphClientMock != null) {
            graphClientMock.close();
            System.out.println("🧹 Mock for GraphServiceClient released");
        }
    }

    @Test
    void sendSimpleEmail() throws Exception {
        RunContext runContext = runContextFactory.of();

        List<String> toRecipients = List.of("recipient@example.com");
        String subject = "Test Subject";
        String body = "<h1>Hello from Kestra</h1>";

        Send task = Send.builder()
            .tenantId(Property.ofValue(MOCK_TENANT_ID))
            .clientId(Property.ofValue(MOCK_CLIENT_ID))
            .clientSecret(Property.ofValue(MOCK_CLIENT_SECRET))
            .from(Property.ofValue(MOCK_FROM_EMAIL))
            .to(Property.ofValue(toRecipients))
            .subject(Property.ofValue(subject))
            .body(Property.ofValue(body))
            .bodyType(Property.ofValue("Html"))
            .build();

        Send.Output output = task.run(runContext);

        assertThat(output, is(notNullValue()));
        assertThat(output.getSubject(), is(subject));
        assertThat(output.getToCount(), is(1));
        assertThat(output.getCcCount(), is(0));
        assertThat(output.getBccCount(), is(0));
        assertThat(output.getBodyType(), is("Html"));
    }

    @Test
    void sendEmailWithCcAndBcc() throws Exception {
        RunContext runContext = runContextFactory.of();

        List<String> toRecipients = Arrays.asList("recipient1@example.com", "recipient2@example.com");
        List<String> ccRecipients = List.of("cc@example.com");
        List<String> bccRecipients = Arrays.asList("bcc1@example.com", "bcc2@example.com");
        String subject = "Test with CC and BCC";
        String body = "Plain text body content";

        Send task = Send.builder()
            .tenantId(Property.ofValue(MOCK_TENANT_ID))
            .clientId(Property.ofValue(MOCK_CLIENT_ID))
            .clientSecret(Property.ofValue(MOCK_CLIENT_SECRET))
            .from(Property.ofValue(MOCK_FROM_EMAIL))
            .to(Property.ofValue(toRecipients))
            .cc(Property.ofValue(ccRecipients))
            .bcc(Property.ofValue(bccRecipients))
            .subject(Property.ofValue(subject))
            .body(Property.ofValue(body))
            .bodyType(Property.ofValue("Text"))
            .build();

        Send.Output output = task.run(runContext);

        assertThat(output, is(notNullValue()));
        assertThat(output.getSubject(), is(subject));
        assertThat(output.getToCount(), is(2));
        assertThat(output.getCcCount(), is(1));
        assertThat(output.getBccCount(), is(2));
        assertThat(output.getBodyType(), is("Text"));
    }

    @Test
    void sendEmailWithDefaultBodyType() throws Exception {
        RunContext runContext = runContextFactory.of();

        List<String> toRecipients = List.of("recipient@example.com");
        String subject = "Test Default Body Type";
        String body = "Body content without explicit type";

        Send task = Send.builder()
            .tenantId(Property.ofValue(MOCK_TENANT_ID))
            .clientId(Property.ofValue(MOCK_CLIENT_ID))
            .clientSecret(Property.ofValue(MOCK_CLIENT_SECRET))
            .from(Property.ofValue(MOCK_FROM_EMAIL))
            .to(Property.ofValue(toRecipients))
            .subject(Property.ofValue(subject))
            .body(Property.ofValue(body))
            .build();

        Send.Output output = task.run(runContext);

        assertThat(output, is(notNullValue()));
        assertThat(output.getSubject(), is(subject));
        assertThat(output.getToCount(), is(1));
        assertThat(output.getBodyType(), is("Html")); // Default value
    }

    @Test
    void sendEmailMultipleRecipients() throws Exception {
        RunContext runContext = runContextFactory.of();

        List<String> toRecipients = Arrays.asList(
            "recipient1@example.com",
            "recipient2@example.com",
            "recipient3@example.com"
        );
        String subject = "Broadcast Message";
        String body = "This is a broadcast email";

        Send task = Send.builder()
            .tenantId(Property.ofValue(MOCK_TENANT_ID))
            .clientId(Property.ofValue(MOCK_CLIENT_ID))
            .clientSecret(Property.ofValue(MOCK_CLIENT_SECRET))
            .from(Property.ofValue(MOCK_FROM_EMAIL))
            .to(Property.ofValue(toRecipients))
            .subject(Property.ofValue(subject))
            .body(Property.ofValue(body))
            .bodyType(Property.ofValue("Html"))
            .build();

        Send.Output output = task.run(runContext);

        assertThat(output, is(notNullValue()));
        assertThat(output.getToCount(), is(3));
        assertThat(output.getCcCount(), is(0));
        assertThat(output.getBccCount(), is(0));
    }
}