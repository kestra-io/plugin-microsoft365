package io.kestra.plugin.microsoft365.dynamics365.businesscentral;

import io.kestra.core.runners.RunContext;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@NoArgsConstructor
public class TestableListCompanies extends ListCompanies {
    @Override
    protected String getAccessToken(RunContext runContext, String scope) {
        return "fake-token";
    }
}
