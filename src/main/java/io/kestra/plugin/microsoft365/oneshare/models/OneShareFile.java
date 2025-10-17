package io.kestra.plugin.microsoft365.oneshare.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.microsoft.graph.models.DriveItem;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Objects;

@Builder
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class OneShareFile {
    private String id;
    private String name;
    private String mimeType;
    private OffsetDateTime createdDateTime;
    private OffsetDateTime lastModifiedDateTime;
    private String webUrl;
    private Long size;
    private boolean isFolder;

    public static OneShareFile of(DriveItem driveItem) {
        Objects.requireNonNull(driveItem, "driveItem cannot be null");
        
        return OneShareFile.builder()
            .id(driveItem.getId())
            .name(driveItem.getName())
            .mimeType(driveItem.getFile() != null ? driveItem.getFile().getMimeType() : null)
            .createdDateTime(driveItem.getCreatedDateTime())
            .lastModifiedDateTime(driveItem.getLastModifiedDateTime())
            .webUrl(driveItem.getWebUrl())
            .size(driveItem.getSize())
            .isFolder(driveItem.getFolder() != null)
            .build();
    }
}