package io.kestra.plugin.microsoft365.outlook;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.models.Message;
import com.microsoft.graph.models.MessageCollectionResponse;
import io.kestra.plugin.microsoft365.outlook.domain.MessageSummary;
import io.kestra.plugin.microsoft365.outlook.utils.GraphMailUtils;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;

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
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    userEmail: "user@example.com"
                    folderId: "inbox"
                    top: 10
                """
        ),
        @Example(
            title = "List emails with filter and store to internal storage",
            full = true,
            code = """
                id: list_filtered_emails
                namespace: company.team

                tasks:
                  - id: list_emails
                    type: io.kestra.plugin.microsoft365.outlook.List
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    userEmail: "user@example.com"
                    folderId: "inbox"
                    filter: "isRead eq false and receivedDateTime ge {{ now() | dateAdd(-7, 'DAYS') | date('yyyy-MM-dd') }}T00:00:00Z"
                    top: 50
                    fetchType: STORE
                """
        ),
        @Example(
            title = "Get first message only",
            full = true,
            code = """
                id: get_first_email
                namespace: company.team

                tasks:
                  - id: get_first
                    type: io.kestra.plugin.microsoft365.outlook.List
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    userEmail: "user@example.com"
                    folderId: "inbox"
                    fetchType: FETCH_ONE
                    top: 1
                """
        )
    }
)
public class List extends AbstractMicrosoftGraphIdentityConnection implements RunnableTask<List.Output> {

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
    @NotNull
    private Property<String> userEmail;

    @Schema(
        title = "The way you want to store the data",
        description = """
            FETCH - outputs the messages as an output
            FETCH_ONE - outputs the first message only as an output
            STORE - stores all messages to a file
            NONE - no output"""
    )
    @NotNull
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

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
        FetchType rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        if (rUser != null && !rUser.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")) {
            throw new IllegalArgumentException("Invalid email format for userEmail: " + rUser);
        }

        logger.info("Listing messages from folder '{}' for user: {} (max: {})", rFolder, rUser != null ? rUser : "current user", rMaxResults);

        // Execute request using proper request configuration
        MessageCollectionResponse messagesResponse = GraphMailUtils.fetchMessages(graphClient, rUser, rFolder, rFilterExpression, rMaxResults, logger);

        if (messagesResponse == null) {
            throw new IllegalStateException("Failed to retrieve messages from Microsoft Graph API");
        }

        java.util.List<Message> messages = messagesResponse.getValue();
        if (messages == null) {
            messages = Collections.emptyList();
        }

        logger.info("Retrieved {} messages", messages.size());

        // Convert messages to domain summaries
        java.util.List<MessageSummary> summaries = new ArrayList<>();
        for (Message message : messages) {
            var summary = MessageSummary.builder()
                .id(message.getId())
                .subject(message.getSubject())
                .senderMail(message.getSender() != null && message.getSender().getEmailAddress() != null ?
                    message.getSender().getEmailAddress().getAddress() : null)
                .fromMail(message.getFrom() != null && message.getFrom().getEmailAddress() != null ?
                    message.getFrom().getEmailAddress().getAddress() : null)
                .receivedDateTime(message.getReceivedDateTime())
                .sentDateTime(message.getSentDateTime())
                .isRead(Boolean.TRUE.equals(message.getIsRead()))
                .hasAttachments(Boolean.TRUE.equals(message.getHasAttachments()))
                .bodyPreview(message.getBodyPreview())
                .importance(message.getImportance() != null ? message.getImportance().toString() : null)
                .conversationId(message.getConversationId())
                .build();
            summaries.add(summary);
        }

        // Handle different fetch types
        Output.OutputBuilder output = Output.builder()
            .count(summaries.size())
            .folderId(rFolder)
            .hasNextPage(messagesResponse.getOdataNextLink() != null);

        switch (rFetchType) {
            case FETCH_ONE -> {
                if (!summaries.isEmpty()) {
                    output.message(summaries.get(0));
                }
            }
            case STORE -> {
                if (!summaries.isEmpty()) {
                    File tempFile = this.storeMessages(runContext, summaries);
                    output.uri(runContext.storage().putFile(tempFile));
                }
            }
            case FETCH -> {
                output.messages(summaries);
            }
            case NONE -> {
                // No output needed
            }
        }

        return output.build();
    }

    private File storeMessages(RunContext runContext, java.util.List<MessageSummary> messages) throws IOException {
        File tempFile = runContext.workingDir().createTempFile(".ion").toFile();

        try (BufferedWriter fileWriter = new BufferedWriter(new FileWriter(tempFile), FileSerde.BUFFER_SIZE)) {
            Flux<MessageSummary> flux = Flux.fromIterable(messages);
            FileSerde.writeAll(fileWriter, flux).block();
        }

        return tempFile;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "List of messages (when fetchType is FETCH)"
        )
        private final java.util.List<MessageSummary> messages;

        @Schema(
            title = "Single message (when fetchType is FETCH_ONE)"
        )
        private final MessageSummary message;

        @Schema(
            title = "URI of the stored messages file (when fetchType is STORE)"
        )
        private final URI uri;

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