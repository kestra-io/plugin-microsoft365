package io.kestra.plugin.microsoft365.outlook;

import com.microsoft.graph.models.Attachment;
import com.microsoft.graph.models.Message;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;

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
    private Property<@Email String> userEmail;

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
        java.util.List<io.kestra.plugin.microsoft365.outlook.domain.AttachmentInfo> attachmentInfos = new ArrayList<>();
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
                .map(att -> io.kestra.plugin.microsoft365.outlook.domain.AttachmentInfo.builder()
                    .id(att.getId())
                    .name(att.getName())
                    .contentType(att.getContentType())
                    .size(att.getSize() != null ? att.getSize().longValue() : null)
                    .build())
                .toList();
        }

        io.kestra.plugin.microsoft365.outlook.domain.MessageDetail detail = io.kestra.plugin.microsoft365.outlook.domain.MessageDetail.builder()
            .id(message.getId())
            .subject(message.getSubject())
            .bodyContent(Objects.requireNonNull(message.getBody()).getContent())
            .bodyType(String.valueOf(message.getBody().getContentType()))
            .bodyPreview(message.getBodyPreview())
            .sender(message.getSender() != null && message.getSender().getEmailAddress() != null ? message.getSender().getEmailAddress().getAddress() : null)
            .from(message.getFrom() != null && message.getFrom().getEmailAddress() != null ? message.getFrom().getEmailAddress().getAddress() : null)
            .toRecipients(message.getToRecipients() != null ? message.getToRecipients().stream().map(r -> r.getEmailAddress() != null ? r.getEmailAddress().getAddress() : null).filter(Objects::nonNull).toList() : java.util.List.of())
            .ccRecipients(message.getCcRecipients() != null ? message.getCcRecipients().stream().map(r -> r.getEmailAddress() != null ? r.getEmailAddress().getAddress() : null).filter(Objects::nonNull).toList() : java.util.List.of())
            .bccRecipients(message.getBccRecipients() != null ? message.getBccRecipients().stream().map(r -> r.getEmailAddress() != null ? r.getEmailAddress().getAddress() : null).filter(Objects::nonNull).toList() : java.util.List.of())
            .receivedDateTime(message.getReceivedDateTime())
            .sentDateTime(message.getSentDateTime())
            .isRead(message.getIsRead())
            .hasAttachments(message.getHasAttachments())
            .importance(message.getImportance() != null ? message.getImportance().toString() : null)
            .conversationId(message.getConversationId())
            .conversationIndex(Arrays.toString(message.getConversationIndex()))
            .internetMessageId(message.getInternetMessageId())
            .attachments(attachmentInfos)
            .build();

        return Output.builder()
            .message(detail)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Message",
            description = "Email message details"
        )
private final io.kestra.plugin.microsoft365.outlook.domain.MessageDetail message;

}
}
