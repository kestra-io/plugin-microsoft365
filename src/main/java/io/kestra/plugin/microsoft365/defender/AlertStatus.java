package io.kestra.plugin.microsoft365.defender;

public enum AlertStatus implements GraphEnum {
    NEW(com.microsoft.graph.models.security.AlertStatus.New),
    IN_PROGRESS(com.microsoft.graph.models.security.AlertStatus.InProgress),
    RESOLVED(com.microsoft.graph.models.security.AlertStatus.Resolved);

    private final com.microsoft.graph.models.security.AlertStatus graphValue;

    AlertStatus(com.microsoft.graph.models.security.AlertStatus graphValue) {
        this.graphValue = graphValue;
    }

    @Override
    public com.microsoft.graph.models.security.AlertStatus toGraph() {
        return graphValue;
    }
}
