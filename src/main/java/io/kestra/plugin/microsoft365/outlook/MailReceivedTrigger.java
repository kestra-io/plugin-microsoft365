package io.kestra.plugin.microsoft365.outlook;

import com.microsoft.graph.models.Attachment;
import com.microsoft.graph.models.Message;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.kestra.core.models.triggers.StatefulTriggerService.*;
import static io.kestra.core.utils.Rethrow.throwFunction;


@Slf4j
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow on new incoming mail in Microsoft Outlook.",
    description = "This trigger will poll every `interval` for new emails in a mailbox folder. " +
        "You can filter messages with an OData `filter` query. "+
        "Once an email is detected, attachments can be downloaded to internal storage."
)
@Plugin(
    examples = {
        @Example(
            title = "Wait for new emails in inbox and iterate through them",
            full = true,
            code = """
                id: outlook_listen
                namespace: company.team

                tasks:
                  - id: each
                    type: io.kestra.plugin.core.flow.EachSequential
                    values: "{{ trigger.messages }}"
                    tasks:
                      - id: log
                        type: io.kestra.plugin.core.debug.Return
                        format: "New email: {{ taskrun.value.subject }}"

                triggers:
                  - id: watch
                    type: io.kestra.plugin.microsoft365.outlook.MailReceivedTrigger
                    interval: PT5M
                    auth:
                      tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                      clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                      clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                      userPrincipalName: "user@example.com"
                    folderId: "inbox"
                """
        ),
        @Example(
            title = "Wait for emails with attachments and process them",
            full = true,
            code = """
                id: outlook_with_attachments
                namespace: company.team

                tasks:
                  - id: each
                    type: io.kestra.plugin.core.flow.EachSequential
                    values: "{{ trigger.messages }}"
                    tasks:
                      - id: process
                        type: io.kestra.plugin.core.debug.Return
                        format: "Processing {{ taskrun.value.subject }} with {{ taskrun.value.attachments | length }} attachments"
                      - id: each_attachment
                        type: io.kestra.plugin.core.flow.EachSequential
                        values: "{{ taskrun.value.attachments }}"
                        tasks:
                          - id: log_attachment
                            type: io.kestra.plugin.core.log.Log
                            message: "Attachment: {{ taskrun.value.name }} stored at {{ taskrun.value.uri }}"

                triggers:
                  - id: watch
                    type: io.kestra.plugin.microsoft365.outlook.MailReceivedTrigger
                    interval: PT2M
                    auth:
                      tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                      clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                      clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                      userPrincipalName: "invoices@company.com"
                    folderId: "inbox"
                    filter: "hasAttachments eq true"
                    includeAttachments: true
                """
        ),
        @Example(
            title = "Wait for emails from specific sender",
            full = true,
            code = """
                id: outlook_filtered_sender
                namespace: company.team

                tasks:
                  - id: process
                    type: io.kestra.plugin.core.log.Log
                    message: "Received {{ trigger.count }} email(s) from important sender"

                triggers:
                  - id: watch
                    type: io.kestra.plugin.microsoft365.outlook.MailReceivedTrigger
                    interval: PT1M
                    auth:
                      tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                      clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                      clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                      userPrincipalName: "user@example.com"
                    filter: "from/emailAddress/address eq 'boss@company.com'"
                    stateTtl: P30D
                """
        )
    }
)
public class MailReceivedTrigger extends io.kestra.plugin.microsoft365.AbstractMicrosoftGraphIdentityPollingTrigger implements TriggerOutput<MailReceivedTrigger.Output>, StatefulTriggerInterface {


    @Builder.Default
    @Schema(
        title = "Polling interval",
        description = "The interval between polls"
    )
    private final Duration interval = Duration.ofMinutes(5);

    @Schema(
        title = "Folder ID or name",
        description = "The mail folder to monitor. Can be a well-known name (inbox, drafts, sentitems, deleteditems) or a folder ID."
    )
    @Builder.Default
    private Property<String> folderId = Property.ofValue("inbox");

    @Schema(
        title = "User email",
        description = "Email address of the user whose mailbox to monitor. If not specified, uses the authenticated user's mailbox."
    )
    private Property<String> userEmail;

    @Schema(
        title = "OData filter",
        description = "Microsoft Graph OData filter query. Example: 'from/emailAddress/address eq \\'sender@example.com\\'' or 'hasAttachments eq true'"
    )
    private Property<String> filter;

    @Schema(
        title = "Include attachments",
        description = "Whether to download attachments to Kestra's internal storage"
    )
    @Builder.Default
    private Property<Boolean> includeAttachments = Property.ofValue(false);

    @Schema(
        title = "Max messages per poll",
        description = "Maximum number of messages to process per polling interval"
    )
    @Builder.Default
    private Property<Integer> maxMessages = Property.ofValue(10);

    @Schema(
        title = "State key",
        description = "Custom key for state management. If not provided, defaults to trigger ID."
    )
    private Property<String> stateKey;

    @Schema(
        title = "State TTL",
        description = "Time to live for state entries. After this duration, messages will be reprocessed."
    )
    private Property<Duration> stateTtl;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var logger = runContext.logger();

        GraphServiceClient graphClient = this.createGraphClient(runContext);

        String rUser = userEmail != null ? runContext.render(userEmail).as(String.class).orElse(null) : null;
        if (rUser == null) {
            rUser = this.getUserPrincipalName(runContext).orElse(null);
        }

        String rFolderId = runContext.render(folderId).as(String.class).orElse("inbox");
        String rFilter = filter != null ? runContext.render(filter).as(String.class).orElse(null) : null;
        Integer rMaxMessages = runContext.render(maxMessages).as(Integer.class).orElse(10);
        Boolean rIncludeAttachments = runContext.render(includeAttachments).as(Boolean.class).orElse(false);

        logger.debug("Polling for messages in folder '{}' for user: {} with filter: {}",
            rFolderId, rUser != null ? rUser : "current user", rFilter);

        // Get messages
        List<Message> messages;
        try {
            if (rUser != null) {
                var request = graphClient.users().byUserId(rUser)
                    .mailFolders().byMailFolderId(rFolderId)
                    .messages()
                    .get(requestConfig -> {
                        if (rFilter != null) {
                            assert requestConfig.queryParameters != null;
                            requestConfig.queryParameters.filter = rFilter;
                        }
                        assert requestConfig.queryParameters != null;
                        requestConfig.queryParameters.top = rMaxMessages;
                        requestConfig.queryParameters.orderby = new String[]{"receivedDateTime DESC"};
                    });
                messages = request != null && request.getValue() != null ? request.getValue() : new ArrayList<>();
            } else {
                var request = graphClient.me()
                    .mailFolders().byMailFolderId(rFolderId)
                    .messages()
                    .get(requestConfig -> {
                        if (rFilter != null) {
                            assert requestConfig.queryParameters != null;
                            requestConfig.queryParameters.filter = rFilter;
                        }
                        assert requestConfig.queryParameters != null;
                        requestConfig.queryParameters.top = rMaxMessages;
                        requestConfig.queryParameters.orderby = new String[]{"receivedDateTime DESC"};
                    });
                messages = request != null && request.getValue() != null ? request.getValue() : new ArrayList<>();
            }
        } catch (Exception e) {
            logger.warn("Failed to retrieve messages: {}", e.getMessage());
            return Optional.empty();
        }

        if (messages.isEmpty()) {
            logger.debug("No messages found");
            return Optional.empty();
        }

        logger.info("Found {} message(s) matching filter", messages.size());

        var rStateKey = runContext.render(stateKey).as(String.class).orElse(defaultKey(context.getNamespace(), context.getFlowId(), id));
        var rStateTtl = runContext.render(stateTtl).as(Duration.class);

        Map<String, StatefulTriggerService.Entry> state = readState(runContext, rStateKey, rStateTtl);

        String finalRUser = rUser;
        List<EmailMessage> toFire = messages.stream()
            .flatMap(throwFunction(message -> {
                String messageUri = String.format("message://%s/%s/%s",
                    finalRUser != null ? finalRUser : "me", rFolderId, message.getId());

                String version = message.getReceivedDateTime() != null
                    ? message.getReceivedDateTime().toString()
                    : "v1";

                Instant receivedAt = message.getReceivedDateTime() != null
                    ? message.getReceivedDateTime().toInstant()
                    : Instant.now();

                var candidate = StatefulTriggerService.Entry.candidate(messageUri, version, receivedAt);
                var update = computeAndUpdateState(state, candidate, runContext.render(getOn()).as(On.class).orElse(On.CREATE));

                if (update.fire()) {
                    logger.debug("New message detected: {}", message.getSubject());

                    // Build email message object
                    EmailMessage emailMessage = buildEmailMessage(message, graphClient, finalRUser, rIncludeAttachments, runContext, logger);
                    return Stream.of(emailMessage);
                }
                return Stream.empty();
            }))
            .toList();

        // Write state back
        writeState(runContext, rStateKey, state, rStateTtl);

        if (toFire.isEmpty()) {
            logger.debug("No new messages to fire after state evaluation");
            return Optional.empty();
        }

        logger.info("Triggering flow with {} new message(s)", toFire.size());

        var output = Output.builder()
            .messages(toFire)
            .count(toFire.size())
            .folderId(rFolderId)
            .build();

        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }

    private EmailMessage buildEmailMessage(Message message, GraphServiceClient client, String user,
                                           Boolean includeAttachments, io.kestra.core.runners.RunContext runContext,
                                           org.slf4j.Logger logger) throws Exception {
        EmailMessage.EmailMessageBuilder emailBuilder = EmailMessage.builder()
            .id(message.getId())
            .subject(message.getSubject())
            .from(message.getFrom() != null && message.getFrom().getEmailAddress() != null ?
                message.getFrom().getEmailAddress().getAddress() : null)
            .fromName(message.getFrom() != null && message.getFrom().getEmailAddress() != null ?
                message.getFrom().getEmailAddress().getName() : null)
            .receivedDateTime(Objects.requireNonNull(message.getReceivedDateTime()).toZonedDateTime())
            .sentDateTime(Objects.requireNonNull(message.getSentDateTime()).toZonedDateTime())
            .hasAttachments(message.getHasAttachments())
            .isRead(message.getIsRead())
            .importance(message.getImportance() != null ? message.getImportance().toString() : null)
            .bodyPreview(message.getBodyPreview())
            .body(message.getBody() != null ? message.getBody().getContent() : null)
            .bodyContentType(message.getBody() != null && message.getBody().getContentType() != null ?
                message.getBody().getContentType().toString() : null);

        if (message.getToRecipients() != null) {
            emailBuilder.toRecipients(message.getToRecipients().stream()
                .map(r -> r.getEmailAddress() != null ? r.getEmailAddress().getAddress() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
        }

        if (message.getCcRecipients() != null) {
            emailBuilder.ccRecipients(message.getCcRecipients().stream()
                .map(r -> r.getEmailAddress() != null ? r.getEmailAddress().getAddress() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
        }

        if (includeAttachments && Boolean.TRUE.equals(message.getHasAttachments())) {
            List<Attachment> attachments;
            if (user != null) {
                var attResponse = client.users().byUserId(user)
                    .messages().byMessageId(Objects.requireNonNull(message.getId()))
                    .attachments().get();
                attachments = attResponse != null && attResponse.getValue() != null ?
                    attResponse.getValue() : new ArrayList<>();
            } else {
                var attResponse = client.me()
                    .messages().byMessageId(Objects.requireNonNull(message.getId()))
                    .attachments().get();
                attachments = attResponse != null && attResponse.getValue() != null ?
                    attResponse.getValue() : new ArrayList<>();
            }

            List<AttachmentData> attachmentData = new ArrayList<>();
            for (Attachment att : attachments) {
                try {
                    URI storedUri = null;

                    if (att.getOdataType() != null && att.getOdataType().contains("fileAttachment")) {
                        byte[] content = getAttachmentContent(att);
                        if (content != null) {
                            File tempFile = runContext.workingDir().createTempFile(att.getName()).toFile();
                            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                                fos.write(content);
                            }
                            storedUri = runContext.storage().putFile(tempFile);
                        }
                    }

                    attachmentData.add(AttachmentData.builder()
                        .id(att.getId())
                        .name(att.getName())
                        .contentType(att.getContentType())
                        .size(att.getSize())
                        .isInline(att.getIsInline())
                        .uri(storedUri)
                        .build());
                } catch (Exception e) {
                    logger.warn("Failed to download attachment {}: {}", att.getName(), e.getMessage());
                }
            }
            emailBuilder.attachments(attachmentData);
        }

        return emailBuilder.build();
    }

    private byte[] getAttachmentContent(Attachment attachment) {
        try {
            var contentField = attachment.getClass().getMethod("getContentBytes");
            return (byte[]) contentField.invoke(attachment);
        } catch (Exception e) {
            log.warn("Could not extract attachment content", e);
            return null;
        }
    }

    @Override
    public Property<On> getOn() {
        return Property.ofValue(On.CREATE);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "List of new messages that triggered the flow")
        private final List<EmailMessage> messages;

        @Schema(title = "Number of new messages")
        private final Integer count;

        @Schema(title = "The folder that was monitored")
        private final String folderId;
    }

    @Builder
    @Getter
    public static class EmailMessage {
        @Schema(title = "Message ID")
        private final String id;

        @Schema(title = "Email subject")
        private final String subject;

        @Schema(title = "Sender email address")
        private final String from;

        @Schema(title = "Sender name")
        private final String fromName;

        @Schema(title = "To recipients")
        private final List<String> toRecipients;

        @Schema(title = "CC recipients")
        private final List<String> ccRecipients;

        @Schema(title = "Date and time the message was received")
        private final ZonedDateTime receivedDateTime;

        @Schema(title = "Date and time the message was sent")
        private final ZonedDateTime sentDateTime;

        @Schema(title = "Whether the message has attachments")
        private final Boolean hasAttachments;

        @Schema(title = "Whether the message has been read")
        private final Boolean isRead;

        @Schema(title = "Message importance (Low, Normal, High)")
        private final String importance;

        @Schema(title = "Preview of the message body")
        private final String bodyPreview;

        @Schema(title = "Full message body content")
        private final String body;

        @Schema(title = "Body content type (Text or HTML)")
        private final String bodyContentType;

        @Schema(title = "List of attachments with URIs to stored files in Kestra")
        private final List<AttachmentData> attachments;
    }

    @Builder
    @Getter
    public static class AttachmentData {
        @Schema(title = "Attachment ID")
        private final String id;

        @Schema(title = "Attachment name")
        private final String name;

        @Schema(title = "Content type")
        private final String contentType;

        @Schema(title = "Size in bytes")
        private final Integer size;

        @Schema(title = "Whether the attachment is inline")
        private final Boolean isInline;

        @Schema(title = "URI to the downloaded attachment in Kestra's internal storage")
        private final URI uri;
    }
}
