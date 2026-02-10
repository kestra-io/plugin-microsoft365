package io.kestra.plugin.microsoft365.outlook;

import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.users.item.sendmail.SendMailPostRequestBody;
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
import java.util.List;
import java.util.stream.Collectors;

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
            .collect(Collectors.toList());
        message.setToRecipients(toRecipientList);

        if (rCcRecipients != null && !rCcRecipients.isEmpty()) {
            List<Recipient> ccRecipientList = rCcRecipients.stream()
                .map(this::createRecipient)
                .collect(Collectors.toList());
            message.setCcRecipients(ccRecipientList);
        }

        if (rBccRecipients != null && !rBccRecipients.isEmpty()) {
            List<Recipient> bccRecipientList = rBccRecipients.stream()
                .map(this::createRecipient)
                .collect(Collectors.toList());
            message.setBccRecipients(bccRecipientList);
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
