package io.kestra.plugin.microsoft365.oneshare;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.microsoft365.AbstractGraphConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
public abstract class AbstractOneShareTask extends AbstractGraphConnection {

    @Schema(
        title = "Drive ID",
        description = "OneDrive or SharePoint drive identifier required for all OneShare tasks"
    )
    @NotNull
    protected Property<String> driveId;
}
