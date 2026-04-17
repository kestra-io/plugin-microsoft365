package io.kestra.plugin.microsoft365.dynamics365.businesscentral;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class ListCompaniesTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldBuildTaskWithRequiredProperties() {
        var task = ListCompanies.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .environment(Property.ofValue("production"))
            .build();

        assertThat(task.getTenantId(), notNullValue());
        assertThat(task.getEnvironment(), notNullValue());
    }

    @Test
    void shouldBuildOutputWithCompanies() {
        var companies = List.of(
            Map.<String, Object>of("id", "company-1", "name", "Cronus International Ltd."),
            Map.<String, Object>of("id", "company-2", "name", "CRONUS USA Inc.")
        );

        var output = ListCompanies.Output.builder()
            .companies(companies)
            .size(companies.size())
            .build();

        assertThat(output.getCompanies(), hasSize(2));
        assertThat(output.getSize(), is(2));
        assertThat(output.getCompanies().getFirst().get("name"), is("Cronus International Ltd."));
    }
}
