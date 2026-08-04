package io.kestra.plugin.microsoft365.teams.adaptivecards;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.graph.models.BodyType;
import com.microsoft.graph.models.ChatMessage;
import com.microsoft.graph.models.ChatMessageAttachment;
import com.microsoft.graph.models.ItemBody;
import io.kestra.core.serializers.JacksonMapper;

import java.util.List;
import java.util.UUID;

/**
 * Builds the {@link ChatMessage} payload shared by {@link Send} and {@link SendToUser}: an Adaptive Card
 * attachment linked to the message body via an {@code <attachment>} tag, which is how Teams knows to render
 * the card instead of a blank message.
 */
final class AdaptiveCardMessage {

    private static final String ADAPTIVE_CARD_CONTENT_TYPE = "application/vnd.microsoft.card.adaptive";

    private AdaptiveCardMessage() {
    }

    static ChatMessage build(String card) throws JsonProcessingException {
        // Fail fast with a clear error rather than letting Graph reject a malformed card payload.
        JacksonMapper.ofJson().readTree(card);

        String attachmentId = UUID.randomUUID().toString();

        ChatMessageAttachment attachment = new ChatMessageAttachment();
        attachment.setId(attachmentId);
        attachment.setContentType(ADAPTIVE_CARD_CONTENT_TYPE);
        attachment.setContent(card);

        ItemBody body = new ItemBody();
        body.setContentType(BodyType.Html);
        body.setContent("<attachment id=\"" + attachmentId + "\"></attachment>");

        ChatMessage message = new ChatMessage();
        message.setBody(body);
        message.setAttachments(List.of(attachment));

        return message;
    }
}
