package io.kestra.plugin.microsoft365.teams.adaptivecards;

import com.microsoft.graph.models.ChatMessage;
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

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Send an Adaptive Card to a Teams channel",
    description = """
        Posts a Microsoft Adaptive Card to a Teams channel via the Microsoft Graph API. Unlike `teams.TeamsIncomingWebhook`, \
        this task targets any channel by ID and returns the created message ID.

        Sending a channel message requires DELEGATED (acting-as-user) authentication: set `username` and `password` so the \
        task authenticates as a real user. App-only `clientSecret` (or `pemCertificate`) credentials are rejected by \
        Microsoft Graph with an HTTP 403, since channel messages cannot be sent with application permissions alone.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Send an Adaptive Card to a Teams channel",
            full = true,
            code = """
                id: send_adaptive_card_to_channel
                namespace: company.team

                tasks:
                  - id: send_card
                    type: io.kestra.plugin.microsoft365.teams.adaptivecards.Send
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    teamId: "{{ secret('TEAMS_TEAM_ID') }}"
                    channelId: "{{ secret('TEAMS_CHANNEL_ID') }}"
                    card: |
                      {
                        "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                        "type": "AdaptiveCard",
                        "version": "1.4",
                        "body": [
                          {
                            "type": "TextBlock",
                            "size": "Large",
                            "weight": "Bolder",
                            "text": "Kestra Pipeline Report"
                          },
                          {
                            "type": "FactSet",
                            "facts": [
                              { "title": "Flow", "value": "{{ flow.id }}" },
                              { "title": "Status", "value": "{{ execution.state.current }}" },
                              { "title": "Execution ID", "value": "{{ execution.id }}" }
                            ]
                          }
                        ],
                        "actions": [
                          {
                            "type": "Action.OpenUrl",
                            "title": "View in Kestra",
                            "url": "{{ kestra.url }}/ui/executions/{{ flow.namespace }}/{{ flow.id }}/{{ execution.id }}"
                          }
                        ]
                      }
                """
        ),
        @Example(
            title = "Send a card on flow failure",
            full = true,
            code = """
                id: monitored_pipeline
                namespace: company.team

                tasks:
                  - id: process
                    type: io.kestra.plugin.scripts.shell.Commands
                    commands:
                      - ./run_pipeline.sh

                errors:
                  - id: alert_team
                    type: io.kestra.plugin.microsoft365.teams.adaptivecards.Send
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    teamId: "{{ secret('TEAMS_TEAM_ID') }}"
                    channelId: "{{ secret('TEAMS_CHANNEL_ID') }}"
                    card: |
                      {
                        "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                        "type": "AdaptiveCard",
                        "version": "1.4",
                        "body": [
                          {
                            "type": "TextBlock",
                            "size": "Large",
                            "weight": "Bolder",
                            "color": "Attention",
                            "text": "Pipeline Failed"
                          },
                          {
                            "type": "TextBlock",
                            "text": "Flow `{{ flow.id }}` failed at {{ execution.state.startDate }}.",
                            "wrap": true
                          }
                        ]
                      }
                """
        ),
        @Example(
            title = "Send an Adaptive Card to a Teams channel using delegated (username/password) authentication",
            full = true,
            code = """
                id: send_adaptive_card_delegated
                namespace: company.team

                tasks:
                  - id: send_card
                    type: io.kestra.plugin.microsoft365.teams.adaptivecards.Send
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    username: "{{ secret('AZURE_USERNAME') }}"
                    password: "{{ secret('AZURE_PASSWORD') }}"
                    teamId: "{{ secret('TEAMS_TEAM_ID') }}"
                    channelId: "{{ secret('TEAMS_CHANNEL_ID') }}"
                    card: |
                      {
                        "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                        "type": "AdaptiveCard",
                        "version": "1.4",
                        "body": [
                          {
                            "type": "TextBlock",
                            "size": "Large",
                            "weight": "Bolder",
                            "text": "Kestra Pipeline Report"
                          },
                          {
                            "type": "FactSet",
                            "facts": [
                              { "title": "Flow", "value": "{{ flow.id }}" },
                              { "title": "Status", "value": "{{ execution.state.current }}" }
                            ]
                          }
                        ]
                      }
                """
        )
    }
)
public class Send extends AbstractGraphConnection implements RunnableTask<Send.Output> {

    @Schema(
        title = "Team ID",
        description = "The Microsoft Teams team identifier that owns the target channel"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> teamId;

    @Schema(
        title = "Channel ID",
        description = "The Teams channel identifier to post the card into"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> channelId;

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

        String rTeamId = runContext.render(this.teamId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("teamId is required"));
        String rChannelId = runContext.render(this.channelId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("channelId is required"));
        String rCard = runContext.render(this.card).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("card is required"));

        ChatMessage message = AdaptiveCardMessage.build(rCard);

        logger.info("Sending Adaptive Card to team '{}', channel '{}'", rTeamId, rChannelId);

        ChatMessage created;
        try {
            created = this.graphClient(runContext)
                .teams().byTeamId(rTeamId)
                .channels().byChannelId(rChannelId)
                .messages().post(message);
        } catch (ApiException e) {
            throw new IllegalStateException(
                String.format("Failed to send Adaptive Card to team '%s', channel '%s' (HTTP %d): %s. Verify the teamId/channelId and that the app has ChannelMessage.Send permission",
                    rTeamId, rChannelId, e.getResponseStatusCode(), e.getMessage()), e);
        }

        if (created == null) {
            throw new IllegalStateException("Microsoft Graph API did not return the created message");
        }

        logger.info("Adaptive Card sent successfully. Message ID: {}", created.getId());

        return Output.builder()
            .messageId(created.getId())
            .createdDateTime(created.getCreatedDateTime() != null ? created.getCreatedDateTime().toString() : null)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Message ID",
            description = "Identifier of the sent chat message, usable to reply or look it up later"
        )
        private final String messageId;

        @Schema(
            title = "Created date time",
            description = "ISO-8601 timestamp of when Microsoft Graph created the message"
        )
        private final String createdDateTime;
    }
}
