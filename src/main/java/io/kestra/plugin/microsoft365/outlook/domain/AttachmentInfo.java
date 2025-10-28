package io.kestra.plugin.microsoft365.outlook.domain;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class AttachmentInfo {
    private final String id;
    private final String name;
    private final String contentType;
    private final Long size;
    private final String uri; // optional for downloaded attachments
}
