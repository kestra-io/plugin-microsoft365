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
class CreateCustomerTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldBuildTaskWithRequiredProperties() {
        var task = CreateCustomer.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .environment(Property.ofValue("production"))
            .companyId(Property.ofValue("00000000-0000-0000-0000-000000000001"))
            .customer(Property.ofValue(Map.of("displayName", "Acme Corp", "email", "billing@acme.com")))
            .build();

        assertThat(task.getCompanyId(), notNullValue());
        assertThat(task.getCustomer(), notNullValue());
    }

    @Test
    void shouldBuildOutputWithCustomerIdAndRecord() {
        var customerData = Map.<String, Object>of(
            "id", "00000000-0000-0000-0000-000000000099",
            "displayName", "Acme Corp"
        );

        var output = CreateCustomer.Output.builder()
            .customerId("00000000-0000-0000-0000-000000000099")
            .customer(customerData)
            .build();

        assertThat(output.getCustomerId(), is("00000000-0000-0000-0000-000000000099"));
        assertThat(output.getCustomer().get("displayName"), is("Acme Corp"));
    }
}
