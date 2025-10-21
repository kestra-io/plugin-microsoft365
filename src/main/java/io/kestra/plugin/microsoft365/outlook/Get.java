package io.kestra.plugin.microsoft365.outlook;

import com.microsoft.graph.models.Attachment;
import com.microsoft.graph.models.AttachmentInfo;
import com.microsoft.graph.models.Message;
import com.microsoft.graph.models.Recipient;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Slf4j
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get a specific emailmessage from Microsoft Outlook",
    description = "Retrieve a specific emai message by its ID."
)
@Plugin(
    examples = {
        @Example(
            title = "Get a specific email message",
            full = true,
            code = """
                id: get_outlook_message
                namespace: company.team

                tasks:
                  - id: get_message
                    type: io.kestra.plugin.microsoft365.outlook.Get
                    auth:
                      tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                      clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                      clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                      userPrincipalName: "user@example.com"
                    messageId: "AAMkAGVmMDEzMTM4LTZmYWUtNDdkNC1hMDZiLTU1OGY5OTZhYmY4OABGAAAAAAAiQ8W967B7TKBjgx9rVEURBwAiIsqMbYjsT5e"
                """
        ),
        @Example(
            title = "Get message from specific user mailbox",
            full = true,
            code = """
                id: get_user_message
                namespace: company.team

                tasks:
                  - id: get_message
                    type: io.kestra.plugin.microsoft365.outlook.Get
                    auth:
                      tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                      clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                      clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                      userPrincipalName: "user@example.com"
                    userEmail: "specific.user@example.com"
                    messageId: "{{ vars.messageId }}"
                    includeAttachments: true
                """
        )
    }
)
public class Get extends io.kestra.plugin.microsoft365.AbstractMicrosoftGraphIdentityConnection implements RunnableTask<Get.Output> {


    @Schema(
        title = "Message ID",
        description = "Uniques identifier of rhe email message to retrieve"
    )
    @NotNull
    private Property<String> messageID;

    @Schema(
        title = "Include attachments",
        description = "Whether to include attachment information in the response"
    )
    @Builder.Default
    private Property<Boolean> includeAttachments = Property.ofValue(false);

    @Schema(
        title = "User email",
        description = "Email address of the user whose mailbox to access (optional, uses authenticated user if not specified)"
    )
    private Property<String> userEmail;

    @Override
    public Output run (RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        GraphServiceClient graphClient = this.createGraphClient(runContext);

        String rMsgId = runContext.render(messageID).as(String.class).orElseThrow();
        String rUser = userEmail != null ? runContext.render(userEmail).as(String.class).orElse(null) : null;
        Boolean rIncludeAttachment = runContext.render(includeAttachments).as(Boolean.class).orElse(false);

        // Fallback to configured UPN if not provided
        if (rUser == null) {
            rUser = this.getUserPrincipalName(runContext).orElse(null);
        }

        logger.info("Retrieving message '{}' for user: {}", rMsgId, rUser != null ? rUser : "current user");

        // Get the message
        Message message;
        if (rUser != null) {
            message = graphClient.users().byUserId(rUser).messages().byMessageId(rMsgId).get();
        } else {
            message = graphClient.me().messages().byMessageId(rMsgId).get();
        }

        assert message != null;
        logger.info("Retrieved message: {}", message.getSubject());

        // Get attachments if requested
        List<AttachmentInfo> attachmentInfos = new ArrayList<>();
        if (rIncludeAttachment && message.getHasAttachments() != null) {
            logger.debug("Retrieving attachment information");

            List<Attachment> attachments;
            if (rUser != null) {
                attachments = Objects.requireNonNull(graphClient.users().byUserId(rUser).messages().byMessageId(rMsgId).attachments().get()).getValue();
            } else {
                attachments = Objects.requireNonNull(graphClient.me().messages().byMessageId(rMsgId).attachments().get()).getValue();
            }

            assert attachments != null;
            attachmentInfos = attachments.stream()
                .map(att -> {
                    AttachmentInfo info = new AttachmentInfo();
                    info.setName(att.getName());
                    info.setContentType(att.getContentType());
                    info.setSize(Objects.requireNonNull(att.getSize()).longValue());
                    return info;
                })
                .toList();
        }

        return Output.builder()
            .id(message.getId())
            .subject(message.getSubject())
            .bodyContent(Objects.requireNonNull(message.getBody()).getContent())
            .bodyType(String.valueOf(message.getBody().getContentType()))
            .bodyPreview(message.getBodyPreview())
            .sender(String.valueOf(message.getSender()))
            .from(String.valueOf(message.getFrom()))
            .toRecipients(message.getToRecipients())
            .ccRecipients(message.getCcRecipients())
            .bccRecipients(message.getBccRecipients())
            .receivedDateTime(message.getReceivedDateTime())
            .sentDateTime(message.getSentDateTime())
            .isRead(message.getIsRead())
            .hasAttachments(message.getHasAttachments())
            .importance(String.valueOf(message.getImportance()))
            .conversationId(message.getConversationId())
            .conversationIndex(Arrays.toString(message.getConversationIndex()))
            .internetMessageId(message.getInternetMessageId())
            .attachments(attachmentInfos)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Message ID",
            description = "Unique identifier of the email message"
        )
        private final String id;

        @Schema(
            title = "Subject",
            description = "Subject line of the email"
        )
        private final String subject;

        @Schema(
            title = "Body content",
            description = "Full body content of the email"
        )
        private final String bodyContent;

        @Schema(
            title = "Body type",
            description = "Content type of the email body (HTML or TEXT)"
        )
        private final String bodyType;

        @Schema(
            title = "Body preview",
            description = "Preview text of the message body"
        )
        private final String bodyPreview;

        @Schema(
            title = "Sender",
            description = "Email address of the sender"
        )
        private final String sender;

        @Schema(
            title = "From",
            description = "Email address in the from field"
        )
        private final String from;

        @Schema(
            title = "To recipients",
            description = "List of email addresses in the to field"
        )
        private final List<Recipient> toRecipients;

        @Schema(
            title = "CC recipients",
            description = "List of email addresses in the cc field"
        )
        private final List<Recipient> ccRecipients;

        @Schema(
            title = "BCC recipients",
            description = "List of email addresses in the bcc field"
        )
        private final List<Recipient> bccRecipients;

        @Schema(
            title = "Received date/time",
            description = "Date and time the message was received"
        )
        private final OffsetDateTime receivedDateTime;

        @Schema(
            title = "Sent date/time",
            description = "Date and time the message was sent"
        )
        private final OffsetDateTime sentDateTime;

        @Schema(
            title = "Is read",
            description = "Whether the message has been read"
        )
        private final Boolean isRead;

        @Schema(
            title = "Has attachments",
            description = "Whether the message has attachments"
        )
        private final Boolean hasAttachments;

        @Schema(
            title = "Importance",
            description = "Importance level of the message"
        )
        private final String importance;

        @Schema(
            title = "Conversation ID",
            description = "Identifier of the conversation thread"
        )
        private final String conversationId;

        @Schema(
            title = "Conversation index",
            description = "Index of the message in the conversation"
        )
        private final String conversationIndex;

        @Schema(
            title = "Internet message ID",
            description = "Internet message ID of the email"
        )
        private final String internetMessageId;

        @Schema(
            title = "Attachments",
            description = "List of attachment information"
        )
        private final List<AttachmentInfo> attachments;
    }

}
