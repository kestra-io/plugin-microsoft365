package io.kestra.plugin.microsoft365.defender;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class GraphEnumTest {

    // Every Kestra value maps to the Graph value with the same name, e.g. IN_PROGRESS -> inProgress
    @Test
    void eachValueMapsToTheGraphValueOfTheSameName() {
        var values = new ArrayList<GraphEnum>();
        values.addAll(List.of(Severity.values()));
        values.addAll(List.of(AlertStatus.values()));
        values.addAll(List.of(IncidentStatus.values()));
        values.addAll(List.of(Classification.values()));
        values.addAll(List.of(Determination.values()));

        for (var value : values) {
            var expected = ((Enum<?>) value).name().toLowerCase().replace("_", "");
            assertThat("%s -> %s".formatted(value, value.toGraph().getValue()), value.toGraph().getValue().toLowerCase(), is(expected));
        }
    }

    @Test
    void eqClauseUsesTheGraphValue() {
        assertThat(DefenderUtils.eqClause("status", IncidentStatus.AWAITING_ACTION), is("status eq 'awaitingAction'"));
        assertThat(DefenderUtils.eqClause("severity", null), is((String) null));
    }
}
