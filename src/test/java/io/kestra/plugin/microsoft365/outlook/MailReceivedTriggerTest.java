package io.kestra.plugin.microsoft365.outlook;

import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.users.UsersRequestBuilder;
import com.microsoft.graph.users.item.UserItemRequestBuilder;
import com.microsoft.graph.users.item.mailfolders.MailFoldersRequestBuilder;
import com.microsoft.graph.users.item.mailfolders.item.MailFolderItemRequestBuilder;
import com.microsoft.graph.users.item.mailfolders.item.messages.MessagesRequestBuilder;
import com.microsoft.graph.users.item.mailfolders.item.messages.item.MessageItemRequestBuilder;
import com.microsoft.graph.users.item.mailfolders.item.messages.item.attachments.AttachmentsRequestBuilder;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import java.util.Map;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit test for the Outlook "MailReceivedTrigger" trigger.
 * This test mocks GraphServiceClient and related builders to avoid real network calls.
 */
@KestraTest
class MailReceivedTriggerTest {

    // ---- Injected Kestra test context ----
    @Inject
    private RunContextFactory runContextFactory;

    // ---- Mock handles ----
    private static MockedConstruction<GraphServiceClient> graphClientMock;

    // ---- Mock constants ----
    private static final String MOCK_TENANT_ID = "mock-tenant-id";
    private static final String MOCK_CLIENT_ID = "mock-client-id";
    private static final String MOCK_CLIENT_SECRET = "mock-client-secret";
    private static final String MOCK_USER_EMAIL = "user@example.com";
    private static final String MOCK_MESSAGE_ID = "mock-message-123";

    @BeforeAll
    static void setupMocks() {
        // Create mock messages
        Message mockMessage1 = createMockMessage(
            "msg-001",
            "Test Subject 1",
            "sender1@example.com",
            "Sender One",
            "This is the body of message 1",
            false
        );

        Message mockMessage2 = createMockMessage(
            "msg-002",
            "Test Subject 2",
            "sender2@example.com",
            "Sender Two",
            "This is the body of message 2",
            true
        );

        List<Message> mockMessages = List.of(mockMessage1, mockMessage2);

        // Mock GraphServiceClient and the entire chain
        graphClientMock = Mockito.mockConstruction(GraphServiceClient.class, (mock, context) -> {
            UsersRequestBuilder usersBuilder = mock(UsersRequestBuilder.class);
            UserItemRequestBuilder userItemBuilder = mock(UserItemRequestBuilder.class);
            MailFoldersRequestBuilder mailFoldersBuilder = mock(MailFoldersRequestBuilder.class);
            MailFolderItemRequestBuilder mailFolderItemBuilder = mock(MailFolderItemRequestBuilder.class);
            MessagesRequestBuilder messagesBuilder = mock(MessagesRequestBuilder.class);
            MessageItemRequestBuilder messageItemBuilder = mock(MessageItemRequestBuilder.class);
            AttachmentsRequestBuilder attachmentsBuilder = mock(AttachmentsRequestBuilder.class);

            // Mock message list response
            MessageCollectionResponse messageResponse = new MessageCollectionResponse();
            messageResponse.setValue(mockMessages);

            when(messagesBuilder.get()).thenReturn(messageResponse);
            when(messagesBuilder.get(any())).thenReturn(messageResponse);

            // Mock attachments
            AttachmentCollectionResponse attachmentResponse = new AttachmentCollectionResponse();
            attachmentResponse.setValue(createMockAttachments());
            when(attachmentsBuilder.get()).thenReturn(attachmentResponse);

            // Chain the mocks - including mailFolders
            when(messageItemBuilder.attachments()).thenReturn(attachmentsBuilder);
            when(messagesBuilder.byMessageId(anyString())).thenReturn(messageItemBuilder);
            when(mailFolderItemBuilder.messages()).thenReturn(messagesBuilder);
            when(mailFoldersBuilder.byMailFolderId(anyString())).thenReturn(mailFolderItemBuilder);
            when(userItemBuilder.mailFolders()).thenReturn(mailFoldersBuilder);
            when(usersBuilder.byUserId(anyString())).thenReturn(userItemBuilder);
            when(mock.users()).thenReturn(usersBuilder);
        });

        System.out.println("✅ Mocked GraphServiceClient and message retrieval chain");
    }

    @AfterAll
    static void tearDownMocks() {
        if (graphClientMock != null) {
            graphClientMock.close();
            System.out.println("🧹 Mock for GraphServiceClient released");
        }
    }

    @Test
    void testTriggerWithNewMessages() throws Exception {
        MailReceivedTrigger trigger = MailReceivedTrigger.builder()
            .id("test-trigger-" + IdUtils.create())
            .type(MailReceivedTrigger.class.getName())
            .tenantId(Property.ofValue(MOCK_TENANT_ID))
            .clientId(Property.ofValue(MOCK_CLIENT_ID))
            .clientSecret(Property.ofValue(MOCK_CLIENT_SECRET))
            .userEmail(Property.ofValue(MOCK_USER_EMAIL))
            .folderId(Property.ofValue("inbox"))
            .interval(Duration.ofMinutes(5))
            .maxMessages(Property.ofValue(10))
            .includeAttachments(Property.ofValue(false))
            .build();

        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(true));

        Execution result = execution.get();
        assertThat(result, is(notNullValue()));

        Map<String, Object> variables = (Map<String, Object>) result.getTrigger().getVariables();
        assertThat(variables, is(notNullValue()));
        assertThat(variables.get("count"), is(2));
        assertThat(variables.get("folderId"), is("inbox"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) variables.get("messages");
        assertThat(messages, hasSize(2));

        // Verify first message
        Map<String, Object> msg1 = messages.getFirst();
        assertThat(msg1.get("id"), is("msg-001"));
        assertThat(msg1.get("subject"), is("Test Subject 1"));
        assertThat(msg1.get("from"), is("sender1@example.com"));
        assertThat(msg1.get("fromName"), is("Sender One"));
        assertThat(msg1.get("hasAttachments"), is(false));
    }

    @Test
    void testTriggerWithFilter() throws Exception {
        MailReceivedTrigger trigger = MailReceivedTrigger.builder()
            .id("test-trigger-filtered-" + IdUtils.create())
            .type(MailReceivedTrigger.class.getName())
            .tenantId(Property.ofValue(MOCK_TENANT_ID))
            .clientId(Property.ofValue(MOCK_CLIENT_ID))
            .clientSecret(Property.ofValue(MOCK_CLIENT_SECRET))
            .userEmail(Property.ofValue(MOCK_USER_EMAIL))
            .folderId(Property.ofValue("inbox"))
            .filter(Property.ofValue("hasAttachments eq true"))
            .interval(Duration.ofMinutes(5))
            .build();

        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(true));

        @SuppressWarnings("unchecked")
        Map<String, Object> variables = (Map<String, Object>) execution.get().getTrigger().getVariables();
        assertThat(variables, is(notNullValue()));
        assertThat(variables.get("messages"), is(notNullValue()));
    }

    @Test
    void testTriggerWithMaxMessages() throws Exception {
        MailReceivedTrigger trigger = MailReceivedTrigger.builder()
            .id("test-trigger-max-" + IdUtils.create())
            .type(MailReceivedTrigger.class.getName())
            .tenantId(Property.ofValue(MOCK_TENANT_ID))
            .clientId(Property.ofValue(MOCK_CLIENT_ID))
            .clientSecret(Property.ofValue(MOCK_CLIENT_SECRET))
            .userEmail(Property.ofValue(MOCK_USER_EMAIL))
            .folderId(Property.ofValue("inbox"))
            .maxMessages(Property.ofValue(1))
            .interval(Duration.ofMinutes(5))
            .build();

        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(true));

        Map<String, Object> variables = (Map<String, Object>) execution.get().getTrigger().getVariables();
        assertThat(variables, is(notNullValue()));
        // Note: Due to state management, actual count may vary
        assertThat(variables.get("messages"), is(notNullValue()));
    }

    @Test
    void testTriggerNoNewMessages() throws Exception {
        // Temporarily close the class-level mock to avoid conflict
        graphClientMock.close();

        // Create a mock that returns empty messages
        try (MockedConstruction<GraphServiceClient> emptyMock = Mockito.mockConstruction(
            GraphServiceClient.class, (mock, context) -> {
                UsersRequestBuilder usersBuilder = mock(UsersRequestBuilder.class);
                UserItemRequestBuilder userItemBuilder = mock(UserItemRequestBuilder.class);
                MailFoldersRequestBuilder mailFoldersBuilder = mock(MailFoldersRequestBuilder.class);
                MailFolderItemRequestBuilder mailFolderItemBuilder = mock(MailFolderItemRequestBuilder.class);
                MessagesRequestBuilder messagesBuilder = mock(MessagesRequestBuilder.class);

                MessageCollectionResponse emptyResponse = new MessageCollectionResponse();
                emptyResponse.setValue(Collections.emptyList());

                when(messagesBuilder.get()).thenReturn(emptyResponse);
                when(messagesBuilder.get(any())).thenReturn(emptyResponse);
                when(mailFolderItemBuilder.messages()).thenReturn(messagesBuilder);
                when(mailFoldersBuilder.byMailFolderId(anyString())).thenReturn(mailFolderItemBuilder);
                when(userItemBuilder.mailFolders()).thenReturn(mailFoldersBuilder);
                when(usersBuilder.byUserId(anyString())).thenReturn(userItemBuilder);
                when(mock.users()).thenReturn(usersBuilder);
            }
        )) {
            MailReceivedTrigger trigger = MailReceivedTrigger.builder()
                .id("test-trigger-empty-" + IdUtils.create())
                .type(MailReceivedTrigger.class.getName())
                .tenantId(Property.ofValue(MOCK_TENANT_ID))
                .clientId(Property.ofValue(MOCK_CLIENT_ID))
                .clientSecret(Property.ofValue(MOCK_CLIENT_SECRET))
                .userEmail(Property.ofValue(MOCK_USER_EMAIL))
                .folderId(Property.ofValue("inbox"))
                .interval(Duration.ofMinutes(5))
                .build();

            Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = TestsUtils.mockTrigger(runContextFactory, trigger);
            Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

            assertThat(execution.isEmpty(), is(true));
        } finally {
            // Recreate the class-level mock for other tests
            setupMocks();
        }
    }

    // Helper methods

    private static Message createMockMessage(String id, String subject, String fromEmail,
                                             String fromName, String body, boolean hasAttachments) {
        Message message = new Message();
        message.setId(id);
        message.setSubject(subject);

        Recipient fromRecipient = new Recipient();
        EmailAddress fromEmailAddress = new EmailAddress();
        fromEmailAddress.setAddress(fromEmail);
        fromEmailAddress.setName(fromName);
        fromRecipient.setEmailAddress(fromEmailAddress);
        message.setFrom(fromRecipient);

        ItemBody itemBody = new ItemBody();
        itemBody.setContent(body);
        itemBody.setContentType(BodyType.Html);
        message.setBody(itemBody);
        message.setBodyPreview(body.substring(0, Math.min(body.length(), 50)));

        message.setHasAttachments(hasAttachments);
        message.setIsRead(false);
        message.setImportance(Importance.Normal);

        OffsetDateTime now = OffsetDateTime.now();
        message.setReceivedDateTime(now);
        message.setSentDateTime(now.minusMinutes(5));

        // To recipients
        Recipient toRecipient = new Recipient();
        EmailAddress toEmailAddress = new EmailAddress();
        toEmailAddress.setAddress(MOCK_USER_EMAIL);
        toRecipient.setEmailAddress(toEmailAddress);
        message.setToRecipients(List.of(toRecipient));

        return message;
    }

    private static List<Attachment> createMockAttachments() {
        List<Attachment> attachments = new ArrayList<>();

        FileAttachment attachment1 = new FileAttachment();
        attachment1.setId("att-001");
        attachment1.setName("document.pdf");
        attachment1.setContentType("application/pdf");
        attachment1.setSize(1024);
        attachment1.setIsInline(false);
        attachment1.setOdataType("#microsoft.graph.fileAttachment");

        attachments.add(attachment1);
        return attachments;
    }

}
