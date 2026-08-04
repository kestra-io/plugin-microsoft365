package io.kestra.plugin.microsoft365.teams.adaptivecards;

import com.microsoft.graph.models.AadUserConversationMember;
import com.microsoft.graph.models.Chat;
import com.microsoft.graph.models.ChatMessage;
import com.microsoft.graph.models.ChatType;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.microsoft365.AbstractGraphConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Send an Adaptive Card to a user's chat",
    description = """
        Resolves (creating if needed) the 1:1 chat between the authenticated identity and a target user, then posts a Microsoft \
        Adaptive Card to it via the Microsoft Graph API.

        Sending a chat message requires DELEGATED (acting-as-user) authentication: set `username` and `password` so the task \
        authenticates as a real user. App-only `clientSecret` (or `pemCertificate`) credentials are rejected by Microsoft Graph \
        with an HTTP 403, since creating or posting to a 1:1 chat is a restricted Graph capability under application permissions.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Send an Adaptive Card to a specific user's chat",
            full = true,
            code = """
                id: notify_user_via_adaptive_card
                namespace: company.team

                tasks:
                  - id: send_to_user
                    type: io.kestra.plugin.microsoft365.teams.adaptivecards.SendToUser
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    userEmail: "oncall@company.com"
                    card: |
                      {
                        "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                        "type": "AdaptiveCard",
                        "version": "1.4",
                        "body": [
                          {
                            "type": "TextBlock",
                            "text": "You have a pending approval for flow **{{ flow.id }}**.",
                            "wrap": true
                          }
                        ],
                        "actions": [
                          {
                            "type": "Action.OpenUrl",
                            "title": "Review",
                            "url": "{{ kestra.url }}/ui/executions/{{ flow.namespace }}/{{ flow.id }}/{{ execution.id }}"
                          }
                        ]
                      }
                """
        ),
        @Example(
            title = "Send an Adaptive Card by user ID instead of email",
            full = true,
            code = """
                id: notify_user_by_id
                namespace: company.team

                tasks:
                  - id: send_to_user
                    type: io.kestra.plugin.microsoft365.teams.adaptivecards.SendToUser
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    userId: "8b081ef6-4792-4def-b2c9-c363a1bf41d5"
                    card: |
                      {
                        "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                        "type": "AdaptiveCard",
                        "version": "1.4",
                        "body": [
                          {
                            "type": "TextBlock",
                            "text": "Flow {{ flow.id }} finished with status {{ execution.state.current }}.",
                            "wrap": true
                          }
                        ]
                      }
                """
        ),
        @Example(
            title = "Send an Adaptive Card to a user's chat using delegated (username/password) authentication",
            full = true,
            code = """
                id: notify_user_delegated
                namespace: company.team

                tasks:
                  - id: send_to_user
                    type: io.kestra.plugin.microsoft365.teams.adaptivecards.SendToUser
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    username: "{{ secret('AZURE_USERNAME') }}"
                    password: "{{ secret('AZURE_PASSWORD') }}"
                    userEmail: "oncall@company.com"
                    card: |
                      {
                        "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                        "type": "AdaptiveCard",
                        "version": "1.4",
                        "body": [
                          {
                            "type": "TextBlock",
                            "text": "You have a pending approval for flow **{{ flow.id }}**.",
                            "wrap": true
                          }
                        ]
                      }
                """
        )
    }
)
public class SendToUser extends AbstractGraphConnection implements RunnableTask<SendToUser.Output> {

    @Schema(
        title = "Target user ID",
        description = "Azure AD object ID of the user to message. Exactly one of `userId` or `userEmail` must be set."
    )
    @PluginProperty(group = "main")
    private Property<String> userId;

    @Schema(
        title = "Target user email",
        description = "User principal name (email) of the user to message. Exactly one of `userId` or `userEmail` must be set."
    )
    @PluginProperty(group = "main")
    private Property<String> userEmail;

    @Schema(
        title = "Adaptive Card payload",
        description = "The Adaptive Card JSON payload, rendered as a Pebble template before being sent. See " +
            "https://adaptivecards.io/explorer/ for the schema reference."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> card;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String rUserId = this.userId != null ? runContext.render(this.userId).as(String.class).orElse(null) : null;
        String rUserEmail = this.userEmail != null ? runContext.render(this.userEmail).as(String.class).orElse(null) : null;

        if ((rUserId == null) == (rUserEmail == null)) {
            throw new IllegalArgumentException("Exactly one of `userId` or `userEmail` must be set, not both or neither");
        }

        String rTargetUser = rUserId != null ? rUserId : rUserEmail;
        String rCard = runContext.render(this.card).as(String.class).orElseThrow();

        GraphServiceClient client = this.graphClient(runContext);

        logger.info("Resolving 1:1 chat with user '{}'", rTargetUser);
        logger.warn("Creating a chat with application permissions is a restricted Graph capability: most tenants require delegated " +
            "permissions, RSC, or a registered Teams bot; this call may fail with a 403 under pure app-only auth");

        AadUserConversationMember member = new AadUserConversationMember();
        member.setOdataType("#microsoft.graph.aadUserConversationMember");
        member.setRoles(List.of("owner"));
        // "user@odata.bind" links the member to an existing user resource; the SDK model has no typed setter for it.
        member.getAdditionalData().put("user@odata.bind", "https://graph.microsoft.com/v1.0/users('" + rTargetUser + "')");

        Chat chat = new Chat();
        chat.setChatType(ChatType.OneOnOne);
        chat.setMembers(List.of(member));

        Chat createdChat;
        try {
            createdChat = client.chats().post(chat);
        } catch (ApiException e) {
            throw new IllegalStateException(
                String.format("Failed to create or resolve chat with user '%s' (HTTP %d): %s. This often means the app registration " +
                        "lacks delegated Chat.ReadWrite permission or RSC consent for app-only chat creation",
                    rTargetUser, e.getResponseStatusCode(), e.getMessage()), e);
        }

        if (createdChat == null || createdChat.getId() == null) {
            throw new IllegalStateException("Microsoft Graph API did not return a chat ID for user '" + rTargetUser + "'");
        }

        String rChatId = createdChat.getId();
        logger.info("Sending Adaptive Card to chat '{}'", rChatId);

        ChatMessage message = AdaptiveCardMessage.build(rCard);

        ChatMessage createdMessage;
        try {
            createdMessage = client.chats().byChatId(rChatId).messages().post(message);
        } catch (ApiException e) {
            throw new IllegalStateException(
                String.format("Failed to send Adaptive Card to chat '%s' (HTTP %d): %s",
                    rChatId, e.getResponseStatusCode(), e.getMessage()), e);
        }

        if (createdMessage == null) {
            throw new IllegalStateException("Microsoft Graph API did not return the created message");
        }

        logger.info("Adaptive Card sent successfully. Chat ID: {}, Message ID: {}", rChatId, createdMessage.getId());

        return Output.builder()
            .chatId(rChatId)
            .messageId(createdMessage.getId())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Chat ID",
            description = "Identifier of the 1:1 chat with the target user"
        )
        private final String chatId;

        @Schema(
            title = "Message ID",
            description = "Identifier of the sent chat message"
        )
        private final String messageId;
    }
}
