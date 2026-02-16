package io.kestra.plugin.microsoft365.outlook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.users.item.sendmail.SendMailPostRequestBody;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Send an email via Microsoft Graph API",
    description = "Send an email message through Outlook using Microsoft Graph API. Supports HTML and plain text content, multiple recipients, attachments, and various email options. Required Microsoft Graph application permission: Mail.Send."
)
@Plugin(
    examples = {
        @Example(
            title = "Send a simple email",
            full = true,
            code = """
                id: send_outlook_email
                namespace: company.team

                tasks:
                  - id: send_email
                    type: io.kestra.plugin.microsoft365.outlook.Send
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    from: "sender@example.com"
                    to:
                      - "recipient@example.com"
                    subject: "Hello from Kestra"
                    body: "<h1>Hello!</h1><p>This email was sent from a Kestra workflow.</p>"
                    bodyType: "Html"
                """
        ),
        @Example(
            title = "Send email with CC, BCC, and plain text",
            full = true,
            code = """
                id: send_detailed_email
                namespace: company.team

                tasks:
                  - id: send_email
                    type: io.kestra.plugin.microsoft365.outlook.Send
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    from: "sender@example.com"
                    to:
                      - "primary@example.com"
                    cc:
                      - "cc1@example.com"
                      - "cc2@example.com"
                    bcc:
                      - "bcc@example.com"
                    subject: "Important Notification"
                    body: "This is a plain text email sent from Kestra workflow."
                    bodyType: "Text"
                """
        ),
        @Example(
            title = "Send email with an attachment",
            full = true,
            code = """
                id: send_email_with_attachment
                namespace: company.team

                inputs:
                  - id: report_uri
                    type: STRING

                tasks:
                  - id: send_email
                    type: io.kestra.plugin.microsoft365.outlook.Send
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    from: "sender@example.com"
                    to:
                      - "recipient@example.com"
                    subject: "Weekly report"
                    body: "See attached report."
                    attachments:
                      - name: "report.csv"
                        uri: "{{ inputs.report_uri }}"
                        contentType: "text/csv"
                """
        )
    }
)
public class Send extends AbstractMicrosoftGraphIdentityConnection implements RunnableTask<Send.Output> {

    @Schema(
        title = "To recipients",
        description = "List of email addresses to send the email to"
    )
    @NotNull
    private Property<List<String>> to;

    @Schema(
        title = "CC recipients",
        description = "List of email addresses to carbon copy"
    )
    private Property<List<String>> cc;

    @Schema(
        title = "BCC recipients",
        description = "List of email addresses to blind carbon copy"
    )
    private Property<List<String>> bcc;

    @Schema(
        title = "Email subject",
        description = "Subject line of the email"
    )
    @NotNull
    private Property<String> subject;

    @Schema(
        title = "Email body",
        description = "Body content of the email"
    )
    @NotNull
    private Property<String> body;

    @Schema(
        title = "Body type",
        description = "Content type of the email body (Html or Text)"
    )
    private Property<String> bodyType;

    @Schema(
        title = "From address",
        description = "Email address of the mailbox used to send the email (required)"
    )
    @NotNull
    private Property<String> from;

    @Schema(
        title = "Email attachments",
        description = "List of attachments to send from Kestra internal storage.",
        anyOf = {List.class, String.class}
    )
    private Property<Object> attachments;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        GraphServiceClient graphClient = this.createGraphClient(runContext);

        // Render properties
        List<String> rToRecipients = runContext.render(to).asList(String.class);
        List<String> rCcRecipients = cc != null ? runContext.render(cc).asList(String.class) : null;
        List<String> rBccRecipients = bcc != null ? runContext.render(bcc).asList(String.class) : null;
        String rEmailSubject = runContext.render(subject).as(String.class).orElseThrow();
        String rEmailBody = runContext.render(body).as(String.class).orElseThrow();
        String rContentType = runContext.render(bodyType).as(String.class).orElse("Html");
        String rFrom = runContext.render(from).as(String.class).orElseThrow();
        Object rAttachments = runContext.render(attachments).as(Object.class).orElse("");

        logger.info("Sending email to {} recipients with subject: {}", rToRecipients.size(), rEmailSubject);

        // Create message
        Message message = new Message();
        message.setSubject(rEmailSubject);

        // Set body
        ItemBody itemBody = new ItemBody();
        itemBody.setContentType(BodyType.valueOf(rContentType));
        itemBody.setContent(rEmailBody);
        message.setBody(itemBody);

        // Set recipients
        List<Recipient> toRecipientList = rToRecipients.stream()
            .map(this::createRecipient)
            .toList();
        message.setToRecipients(toRecipientList);

        if (rCcRecipients != null && !rCcRecipients.isEmpty()) {
            List<Recipient> ccRecipientList = rCcRecipients.stream()
                .map(this::createRecipient)
                .toList();
            message.setCcRecipients(ccRecipientList);
        }

        if (rBccRecipients != null && !rBccRecipients.isEmpty()) {
            List<Recipient> bccRecipientList = rBccRecipients.stream()
                .map(this::createRecipient)
                .toList();
            message.setBccRecipients(bccRecipientList);
        }

        var attachmentList = this.getAttachments(rAttachments);
        if (!attachmentList.isEmpty()) {
            message.setAttachments(this.createMessageAttachments(attachmentList, runContext));
            logger.debug("Added {} attachment(s) to outgoing email", attachmentList.size());
        }

        // Create send mail request body
        SendMailPostRequestBody postRequest = new SendMailPostRequestBody();
        postRequest.setMessage(message);

        // Send email
        graphClient.users().byUserId(rFrom).sendMail().post(postRequest);
        logger.info("Email sent successfully from: {}", rFrom);

        return Output.builder()
            .subject(rEmailSubject)
            .toCount(rToRecipients.size())
            .ccCount(rCcRecipients != null ? rCcRecipients.size() : 0)
            .bccCount(rBccRecipients != null ? rBccRecipients.size() : 0)
            .bodyType(rContentType)
            .build();
    }

    private Recipient createRecipient(String email) {
        Recipient recipient = new Recipient();
        EmailAddress emailAddress = new EmailAddress();
        emailAddress.setAddress(email);
        recipient.setEmailAddress(emailAddress);
        return recipient;
    }

    private List<Attachment> createMessageAttachments(List<AttachmentInput> attachments, RunContext runContext) throws Exception {
        var graphAttachments = new ArrayList<Attachment>();

        for (var attachment : attachments) {
            var rUri = runContext.render(attachment.getUri()).as(String.class).orElseThrow();
            var rName = runContext.render(attachment.getName()).as(String.class).orElseThrow();
            var rContentType = runContext.render(attachment.getContentType()).as(String.class).orElse("application/octet-stream");

            byte[] content;
            try (var inputStream = runContext.storage().getFile(URI.create(rUri))) {
                content = inputStream.readAllBytes();
            }

            var fileAttachment = new FileAttachment();
            fileAttachment.setOdataType("#microsoft.graph.fileAttachment");
            fileAttachment.setName(rName);
            fileAttachment.setContentType(rContentType);
            fileAttachment.setContentBytes(content);
            graphAttachments.add(fileAttachment);
        }

        return graphAttachments;
    }

    private List<AttachmentInput> getAttachments(Object attachments) throws JsonProcessingException {
        switch (attachments) {
            case null -> {
                return List.of();
            }
            case List<?> list -> {
                if (list.isEmpty()) {
                    return List.of();
                }

                if (list.getFirst() instanceof AttachmentInput) {
                    @SuppressWarnings("unchecked")
                    var typed = (List<AttachmentInput>) list;
                    return typed;
                } else {
                    @SuppressWarnings("unchecked")
                    var items = (List<Map<String, Object>>) list;
                    return toAttachments(items);
                }
            }
            case String content -> {
                var trimmed = content.trim();
                if (trimmed.isEmpty()) {
                    return List.of();
                }

                if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                    return parseJsonAttachmentString(trimmed);
                }

                var innerJson = JacksonMapper.ofJson().readValue(trimmed, String.class);
                return parseJsonAttachmentString(innerJson);
            }
            default -> {
            }
        }

        throw new IllegalArgumentException("The `attachments` attribute must be a String or a List");
    }

    private List<AttachmentInput> parseJsonAttachmentString(String json) throws JsonProcessingException {
        var trimmed = json.trim();
        if (trimmed.startsWith("[")) {
            List<Map<String, Object>> items = JacksonMapper.ofJson().readValue(trimmed, new TypeReference<>() {
            });
            return toAttachments(items);
        } else if (trimmed.startsWith("{")) {
            Map<String, Object> item = JacksonMapper.ofJson().readValue(trimmed, new TypeReference<>() {
            });
            return toAttachments(List.of(item));
        } else {
            return List.of();
        }
    }

    private static List<AttachmentInput> toAttachments(List<Map<String, Object>> items) {
        return items.stream()
            .map(item -> AttachmentInput.builder()
                .name(Property.ofValue((String) item.get("name")))
                .uri(Property.ofValue((String) item.get("uri")))
                .contentType(Property.ofValue((String) item.getOrDefault("contentType", "application/octet-stream")))
                .build())
            .toList();
    }

    @Getter
    @Builder
    public static class AttachmentInput {
        @Schema(
            title = "An attachment URI from Kestra internal storage"
        )
        @NotNull
        private Property<String> uri;

        @Schema(
            title = "Attachment file name"
        )
        @NotNull
        private Property<String> name;

        @Schema(
            title = "Attachment content type",
            description = "MIME type for the attachment. Defaults to application/octet-stream."
        )
        @NotNull
        @Builder.Default
        private Property<String> contentType = Property.ofValue("application/octet-stream");
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Email subject",
            description = "Subject line of the sent email"
        )
        private final String subject;

        @Schema(
            title = "To recipient count",
            description = "Number of recipients in the 'to' field"
        )
        private final int toCount;

        @Schema(
            title = "CC recipient count",
            description = "Number of recipients in the 'cc' field"
        )
        private final int ccCount;

        @Schema(
            title = "BCC recipient count",
            description = "Number of recipients in the 'bcc' field"
        )
        private final int bccCount;

        @Schema(
            title = "Body type",
            description = "Content type of the email body (Html or Text)"
        )
        private final String bodyType;
    }
}
