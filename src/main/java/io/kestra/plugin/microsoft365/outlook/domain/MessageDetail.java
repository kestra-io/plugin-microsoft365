package io.kestra.plugin.microsoft365.outlook.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

@Builder
@Getter
public class MessageDetail {
    private final String id;
    private final String subject;
    private final String bodyContent;
    private final String bodyType;
    private final String bodyPreview;

    private final String senderMail;
    private final String fromMail;
    private final List<String> toRecipients;
    private final List<String> ccRecipients;
    private final List<String> bccRecipients;

    private final OffsetDateTime receivedDateTime;
    private final OffsetDateTime sentDateTime;
    private final Boolean isRead;
    private final Boolean hasAttachments;
    private final String importance;
    private final String conversationId;
    private final String conversationIndex;
    private final String internetMessageId;

    private final List<AttachmentInfo> attachments;
}
