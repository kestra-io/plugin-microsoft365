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
        title = "Item ID"
    )
    String id;

    @Schema(
        title = "Item name"
    )
    String name;

    @Schema(
        title = "Item size in bytes"
    )
    Long size;

    @Schema(
        title = "Creation time of the item"
    )
    OffsetDateTime createdDateTime;

    @Schema(
        title = "Last modified time of the item"
    )
    OffsetDateTime lastModifiedDateTime;

    @Schema(
        title = "Web URL of the item"
    )
    String webUrl;

    @Schema(
        title = "Whether the item is a folder"
    )
    Boolean isFolder;

    @Schema(
        title = "Whether the item is a file"
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
