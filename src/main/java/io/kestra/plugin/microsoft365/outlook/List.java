package io.kestra.plugin.microsoft365.outlook;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.models.Message;
import com.microsoft.graph.models.MessageCollectionResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.ArrayList;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List emails from Microsoft Outlook",
    description = "Retrieve a list of email messages from a specific folder using Microsoft Graph API. Supports filtering with OData syntax and pagination."
)
@Plugin(
    examples = {
        @Example(
            title = "List recent emails from inbox",
            full = true,
            code = """
                id: list_outlook_emails
                namespace: company.team

                tasks:
                  - id: list_emails
                    type: io.kestra.plugin.microsoft365.outlook.List
                    auth:
                      tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                      clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                      clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                      userPrincipalName: "user@example.com"
                    folderId: "inbox"
                    top: 10
                """
        ),
        @Example(
            title = "List emails with filter",
            full = true,
            code = """
                id: list_filtered_emails
                namespace: company.team

                tasks:
                  - id: list_emails
                    type: io.kestra.plugin.microsoft365.outlook.List
                    auth:
                      tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                      clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                      clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                      userPrincipalName: "user@example.com"
                    folderId: "inbox"
                    filter: "isRead eq false and receivedDateTime ge {{ now() | dateAdd(-7, 'DAYS') | date('yyyy-MM-dd') }}T00:00:00Z"
                    top: 50
                """
        )
    }
)
public class List extends io.kestra.plugin.microsoft365.AbstractMicrosoftGraphIdentityConnection implements RunnableTask<List.Output> {


    @Schema(
        title = "Folder ID",
        description = "Email folder to retrieve messages from (inbox, sentitems, drafts, deleteditems, or folder ID)",
        defaultValue = "inbox"
    )
    private Property<String> folderId;

    @Schema(
        title = "Filter",
        description = "OData filter expression to filter the results (e.g., 'isRead eq false', 'from/emailAddress/address eq 'sender@example.com'')"
    )
    private Property<String> filter;

    @Schema(
        title = "Top",
        description = "Maximum number of messages to return",
        defaultValue = "50"
    )
    private Property<Integer> top;

    @Schema(
        title = "User email",
        description = "Email address of the user whose mailbox to access (optional, uses authenticated user if not specified)"
    )
    private Property<String> userEmail;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        // Create Graph service client from abstract connection
        GraphServiceClient graphClient = this.createGraphClient(runContext);

        // Render properties
        String rFolder = runContext.render(folderId).as(String.class).orElse("inbox");
        String rFilterExpression = filter != null ? runContext.render(filter).as(String.class).orElse(null) : null;
        Integer rMaxResults = runContext.render(top).as(Integer.class).orElse(50);
        String rUser = userEmail != null ? runContext.render(userEmail).as(String.class).orElse(null) : null;

        // Fallback to configured UPN if not provided
        if (rUser == null) {
            rUser = this.getUserPrincipalName(runContext).orElse(null);
        }

        logger.info("Listing messages from rFolder '{}' for rUser: {} (max: {})", rFolder, rUser != null ? rUser : "current rUser", rMaxResults);

        // Execute request using proper request configuration
        MessageCollectionResponse messagesResponse;
        if (rUser != null) {
            messagesResponse = graphClient.users().byUserId(rUser)
                .mailFolders().byMailFolderId(rFolder)
                .messages()
                .get(requestConfig -> {
                    if (rFilterExpression != null) {
                        assert requestConfig.queryParameters != null;
                        requestConfig.queryParameters.filter = rFilterExpression;
                        logger.debug("Applied filter: {}", rFilterExpression);
                    }
                    assert requestConfig.queryParameters != null;
                    requestConfig.queryParameters.top = rMaxResults;
                    requestConfig.queryParameters.orderby = new String[]{"receivedDateTime DESC"};
                    requestConfig.queryParameters.select = new String[]{"id", "subject", "from", "sender", "receivedDateTime", "sentDateTime", "isRead", "hasAttachments", "bodyPreview", "importance", "conversationId"};
                });
        } else {
            messagesResponse = graphClient.me()
                .mailFolders().byMailFolderId(rFolder)
                .messages()
                .get(requestConfig -> {
                    if (rFilterExpression != null) {
                        assert requestConfig.queryParameters != null;
                        requestConfig.queryParameters.filter = rFilterExpression;
                        logger.debug("Applied filter: {}", rFilterExpression);
                    }
                    assert requestConfig.queryParameters != null;
                    requestConfig.queryParameters.top = rMaxResults;
                    requestConfig.queryParameters.orderby = new String[]{"receivedDateTime DESC"};
                    requestConfig.queryParameters.select = new String[]{"id", "subject", "from", "sender", "receivedDateTime", "sentDateTime", "isRead", "hasAttachments", "bodyPreview", "importance", "conversationId"};
                });
        }

        assert messagesResponse != null;
        java.util.List<Message> messages = messagesResponse.getValue();

        assert messages != null;
        logger.info("Retrieved {} messages", messages.size());

        // Convert messages to output format
        java.util.List<EmailMessage> emailMessages = new ArrayList<>();
        for (Message message : messages) {
            EmailMessage emailMessage = EmailMessage.builder()
                .id(message.getId())
                .subject(message.getSubject())
                .sender(message.getSender() != null && message.getSender().getEmailAddress() != null ?
                    message.getSender().getEmailAddress().getAddress() : null)
                .from(message.getFrom() != null && message.getFrom().getEmailAddress() != null ?
                    message.getFrom().getEmailAddress().getAddress() : null)
                .receivedDateTime(message.getReceivedDateTime())
                .sentDateTime(message.getSentDateTime())
                .isRead(message.getIsRead() != null ? message.getIsRead() : false)
                .hasAttachments(message.getHasAttachments() != null ? message.getHasAttachments() : false)
                .bodyPreview(message.getBodyPreview())
                .importance(message.getImportance() != null ? message.getImportance().toString() : null)
                .conversationId(message.getConversationId())
                .build();
            emailMessages.add(emailMessage);
        }

        return Output.builder()
            .messages(emailMessages)
            .count(emailMessages.size())
            .folderId(rFolder)
            .hasNextPage(messagesResponse.getOdataNextLink() != null)
            .build();
    }

    @Builder
    @Getter
    public static class EmailMessage {
        @Schema(title = "Message ID", description = "Unique identifier of the email message")
        private final String id;

        @Schema(title = "Subject", description = "Subject line of the email")
        private final String subject;

        @Schema(title = "Sender", description = "Email address of the sender")
        private final String sender;

        @Schema(title = "From", description = "Email address in the from field")
        private final String from;

        @Schema(title = "Received date/time", description = "Date and time the message was received")
        private final OffsetDateTime receivedDateTime;

        @Schema(title = "Sent date/time", description = "Date and time the message was sent")
        private final OffsetDateTime sentDateTime;

        @Schema(title = "Is read", description = "Whether the message has been read")
        private final Boolean isRead;

        @Schema(title = "Has attachments", description = "Whether the message has attachments")
        private final Boolean hasAttachments;

        @Schema(title = "Body preview", description = "Preview text of the message body")
        private final String bodyPreview;

        @Schema(title = "Importance", description = "Importance level of the message")
        private final String importance;

        @Schema(title = "Conversation ID", description = "Identifier of the conversation thread")
        private final String conversationId;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Email messages",
            description = "List of retrieved email messages"
        )
        private final java.util.List<EmailMessage> messages;

        @Schema(
            title = "Message count",
            description = "Number of messages retrieved"
        )
        private final int count;

        @Schema(
            title = "Folder ID",
            description = "ID of the folder that was queried"
        )
        private final String folderId;

        @Schema(
            title = "Has next page",
            description = "Whether there are more messages available"
        )
        private final Boolean hasNextPage;
    }
}