package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.models.security.AlertSeverity;

/**
 * Severity of a Defender alert or incident, mapped to the Microsoft Graph `alertSeverity` values.
 */
public enum Severity implements GraphEnum {
    INFORMATIONAL(AlertSeverity.Informational),
    LOW(AlertSeverity.Low),
    MEDIUM(AlertSeverity.Medium),
    HIGH(AlertSeverity.High);

    private final AlertSeverity graphValue;

    Severity(AlertSeverity graphValue) {
        this.graphValue = graphValue;
    }

    @Override
    public AlertSeverity toGraph() {
        return graphValue;
    }
}
