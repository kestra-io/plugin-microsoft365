package io.kestra.plugin.microsoft365.sharepoint.models;

import com.microsoft.graph.models.DriveItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;

@Value
@Builder
public class Item {
    @Schema(
        title = "The unique identifier of the item."
    )
    String id;

    @Schema(
        title = "The name of the item."
    )
    String name;

    @Schema(
        title = "The size of the item in bytes."
    )
    Long size;

    @Schema(
        title = "The creation time of the item."
    )
    OffsetDateTime createdDateTime;

    @Schema(
        title = "The last modified time of the item."
    )
    OffsetDateTime lastModifiedDateTime;

    @Schema(
        title = "The web URL of the item."
    )
    String webUrl;

    @Schema(
        title = "Whether the item is a folder."
    )
    Boolean isFolder;

    @Schema(
        title = "Whether the item is a file."
    )
    Boolean isFile;

    public static Item fromDriveItem(DriveItem driveItem) {
        return Item.builder()
            .id(driveItem.getId())
            .name(driveItem.getName())
            .size(driveItem.getSize())
            .createdDateTime(driveItem.getCreatedDateTime())
            .lastModifiedDateTime(driveItem.getLastModifiedDateTime())
            .webUrl(driveItem.getWebUrl())
            .isFolder(driveItem.getFolder() != null)
            .isFile(driveItem.getFile() != null)
            .build();
    }
}
