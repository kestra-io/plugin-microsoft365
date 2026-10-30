package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.models.security.AlertSeverity;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.microsoft365.AbstractGraphConnection;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Base class of the Microsoft Defender XDR tasks: reuses the Microsoft Graph connection of the plugin
 * (tenantId, clientId, clientSecret or pemCertificate) and holds the shared list helpers.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractDefender extends AbstractGraphConnection {
    static final int DEFAULT_TOP = 100;
    static final int MAX_TOP = 2000;

    protected int renderTop(RunContext runContext, Property<Integer> top) throws IllegalVariableEvaluationException {
        int rTop = runContext.render(top).as(Integer.class).orElse(DEFAULT_TOP);
        if (rTop < 1 || rTop > MAX_TOP) {
            throw new IllegalArgumentException("top must be between 1 and " + MAX_TOP + ", got " + rTop);
        }
        return rTop;
    }

    protected String renderFilter(RunContext runContext, Property<String> filter, Property<String> severity, String statusClause)
        throws IllegalVariableEvaluationException {
        String rFilter = runContext.render(filter).as(String.class).orElse(null);
        AlertSeverity rSeverity = DefenderUtils.parseEnum(
            AlertSeverity.class, runContext.render(severity).as(String.class).orElse(null), "severity");
        return DefenderUtils.combineFilters(rFilter, DefenderUtils.eqClause("severity", rSeverity), statusClause);
    }

    /**
     * Fills the FETCH / FETCH_ONE / STORE fields of a list output.
     */
    protected static Fetched fetch(RunContext runContext, FetchType fetchType, List<Map<String, Object>> items) throws IOException {
        return switch (fetchType) {
            case FETCH -> new Fetched(items, null, null);
            case FETCH_ONE -> new Fetched(null, items.isEmpty() ? null : items.getFirst(), null);
            case STORE -> new Fetched(null, null, DefenderUtils.store(runContext, items));
            case NONE -> new Fetched(null, null, null);
        };
    }

    protected record Fetched(List<Map<String, Object>> items, Map<String, Object> first, URI uri) {
    }
}
