package io.kestra.plugin.microsoft365.oneshare;

import io.kestra.plugin.microsoft365.AbstractGraphConnection;
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
}
