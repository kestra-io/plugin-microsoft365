package io.kestra.plugin.microsoft365.dynamics365.dataverse;

import io.kestra.core.runners.RunContext;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@NoArgsConstructor
public class TestableQuery extends Query {
    @Override
    protected String getAccessToken(RunContext runContext, String scope) {
        return "fake-token";
    }
}
