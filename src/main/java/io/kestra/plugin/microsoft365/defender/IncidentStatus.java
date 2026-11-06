package io.kestra.plugin.microsoft365.defender;

/**
 * Status of a Defender incident, mapped to the Microsoft Graph `incidentStatus` values.
 */
public enum IncidentStatus implements GraphEnum {
    ACTIVE(com.microsoft.graph.models.security.IncidentStatus.Active),
    IN_PROGRESS(com.microsoft.graph.models.security.IncidentStatus.InProgress),
    RESOLVED(com.microsoft.graph.models.security.IncidentStatus.Resolved),
    REDIRECTED(com.microsoft.graph.models.security.IncidentStatus.Redirected),
    AWAITING_ACTION(com.microsoft.graph.models.security.IncidentStatus.AwaitingAction);

    private final com.microsoft.graph.models.security.IncidentStatus graphValue;

    IncidentStatus(com.microsoft.graph.models.security.IncidentStatus graphValue) {
        this.graphValue = graphValue;
    }

    @Override
    public com.microsoft.graph.models.security.IncidentStatus toGraph() {
        return graphValue;
    }
}
