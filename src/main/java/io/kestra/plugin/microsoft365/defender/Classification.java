package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.models.security.AlertClassification;

/**
 * Classification of a Defender alert or incident, mapped to the Microsoft Graph `alertClassification` values.
 */
public enum Classification implements GraphEnum {
    UNKNOWN(AlertClassification.Unknown),
    FALSE_POSITIVE(AlertClassification.FalsePositive),
    TRUE_POSITIVE(AlertClassification.TruePositive),
    INFORMATIONAL_EXPECTED_ACTIVITY(AlertClassification.InformationalExpectedActivity);

    private final AlertClassification graphValue;

    Classification(AlertClassification graphValue) {
        this.graphValue = graphValue;
    }

    @Override
    public AlertClassification toGraph() {
        return graphValue;
    }
}
