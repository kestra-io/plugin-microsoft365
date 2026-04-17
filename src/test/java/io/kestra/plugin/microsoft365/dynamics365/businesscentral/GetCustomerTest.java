package io.kestra.plugin.microsoft365.dynamics365.businesscentral;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class GetCustomerTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldBuildTaskWithRequiredProperties() {
        var task = GetCustomer.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .environment(Property.ofValue("production"))
            .companyId(Property.ofValue("00000000-0000-0000-0000-000000000001"))
            .customerId(Property.ofValue("00000000-0000-0000-0000-000000000002"))
            .build();

        assertThat(task.getCompanyId(), notNullValue());
        assertThat(task.getCustomerId(), notNullValue());
        assertThat(task.getEnvironment(), notNullValue());
    }

    @Test
    void shouldBuildOutputWithCustomer() {
        var customerData = Map.<String, Object>of("id", "00000000-0000-0000-0000-000000000002", "displayName", "Acme Corp");

        var output = GetCustomer.Output.builder()
            .customer(customerData)
            .build();

        assertThat(output.getCustomer(), notNullValue());
        assertThat(output.getCustomer().get("displayName"), is("Acme Corp"));
    }
}
