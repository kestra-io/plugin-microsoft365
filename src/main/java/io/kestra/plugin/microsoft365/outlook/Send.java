package io.kestra.plugin.microsoft365.outlook;

import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.users.item.sendmail.SendMailPostRequestBody;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import jakarta.validation.Valid;
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
    description = "Send an email message through Outlook using Microsoft Graph API. Supports HTML and plain text content, multiple recipients, attachments, and various email options."
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
                    auth:
                      tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                      clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                      clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    to:
                      - "recipient@example.com"
                    subject: "Hello from Kestra"
                    body: "<h1>Hello!</h1><p>This email was sent from a Kestra workflow.</p>"
                    bodyType: "HTML"
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
                    auth:
                      tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                      clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                      clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                      userPrincipalName: "sender@example.com"
                    to:
                      - "primary@example.com"
                    cc:
                      - "cc1@example.com"
                      - "cc2@example.com"
                    bcc:
                      - "bcc@example.com"
                    subject: "Important Notification"
                    body: "This is a plain text email sent from Kestra workflow."
                    bodyType: "TEXT"
                """
        )
    }
)
public class Send extends Task implements RunnableTask<Send.Output> {

    @Schema(
        title = "Authentication configuration",
        description = "Microsoft Graph authentication settings including tenant ID, client ID, and client secret"
    )
    @Valid
    @NotNull
    private GraphAuthConfig auth;

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
        description = "Content type of the email body (HTML or TEXT)",
        defaultValue = "HTML"
    )
    private Property<String> bodyType;

    @Schema(
        title = "From address",
        description = "Email address to send from (optional, uses authenticated user if not specified)"
    )
    private Property<String> from;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        // Create Graph service client
        GraphServiceClient graphClient = GraphService.createClientCredentialClient(auth, runContext);

        // Render properties
        List<String> toRecipients = runContext.render(to).asList(String.class);
        List<String> ccRecipients = cc != null ? runContext.render(cc).asList(String.class) : null;
        List<String> bccRecipients = bcc != null ? runContext.render(bcc).asList(String.class) : null;
        String emailSubject = runContext.render(subject).as(String.class).orElseThrow();
        String emailBody = runContext.render(body).as(String.class).orElseThrow();
        String contentType = runContext.render(bodyType).as(String.class).orElse("HTML");

        logger.info("Sending email to {} recipients with subject: {}", toRecipients.size(), emailSubject);

        // Create message
        Message message = new Message();
        message.setSubject(emailSubject);

        // Set body
        ItemBody itemBody = new ItemBody();
        itemBody.setContentType(BodyType.valueOf(contentType));
        itemBody.setContent(emailBody);
        message.setBody(itemBody);

        // Set recipients
        List<Recipient> toRecipientList = toRecipients.stream()
            .map(this::createRecipient)
            .collect(Collectors.toList());
        message.setToRecipients(toRecipientList);

        if (ccRecipients != null && !ccRecipients.isEmpty()) {
            List<Recipient> ccRecipientList = ccRecipients.stream()
                .map(this::createRecipient)
                .collect(Collectors.toList());
            message.setCcRecipients(ccRecipientList);
        }

        if (bccRecipients != null && !bccRecipients.isEmpty()) {
            List<Recipient> bccRecipientList = bccRecipients.stream()
                .map(this::createRecipient)
                .collect(Collectors.toList());
            message.setBccRecipients(bccRecipientList);
        }

        // Determine sender
        String senderEmail = from != null ? runContext.render(from).as(String.class).orElse(null) : null;
        if (senderEmail == null && auth.getUserPrincipalName() != null) {
            senderEmail = runContext.render(auth.getUserPrincipalName()).as(String.class).orElse(null);
        }

        // Create send mail request body
        SendMailPostRequestBody postRequest = new SendMailPostRequestBody();
        postRequest.setMessage(message);

        // Send email
        if (senderEmail != null) {
            // Send on behalf of specific user
            graphClient.users().byUserId(senderEmail).sendMail().post(postRequest);
            logger.info("Email sent successfully from: {}", senderEmail);
        } else {
            // Send using application identity (requires appropriate permissions)
            graphClient.me().sendMail().post(postRequest);
            logger.info("Email sent successfully using application identity");
        }

        return Output.builder()
            .subject(emailSubject)
            .toCount(toRecipients.size())
            .ccCount(ccRecipients != null ? ccRecipients.size() : 0)
            .bccCount(bccRecipients != null ? bccRecipients.size() : 0)
            .bodyType(contentType)
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
            description = "Content type of the email body (HTML or TEXT)"
        )
        private final String bodyType;
    }
}