package io.kestra.plugin.microsoft365.outlook.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Builder
@Getter
public class MessageSummary {
    private final String id;
    private final String subject;
    private final String senderMail;
    private final String fromMail;
    private final OffsetDateTime receivedDateTime;
    private final OffsetDateTime sentDateTime;
    private final Boolean isRead;
    private final Boolean hasAttachments;
    private final String bodyPreview;
    private final String importance;
    private final String conversationId;
}
