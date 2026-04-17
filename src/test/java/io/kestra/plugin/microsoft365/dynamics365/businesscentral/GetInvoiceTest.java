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
class GetInvoiceTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldBuildTaskWithRequiredProperties() {
        var task = GetInvoice.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .environment(Property.ofValue("production"))
            .companyId(Property.ofValue("00000000-0000-0000-0000-000000000001"))
            .invoiceId(Property.ofValue("00000000-0000-0000-0000-000000000003"))
            .build();

        assertThat(task.getCompanyId(), notNullValue());
        assertThat(task.getInvoiceId(), notNullValue());
        assertThat(task.getEnvironment(), notNullValue());
    }

    @Test
    void shouldBuildOutputWithInvoice() {
        var invoiceData = Map.<String, Object>of(
            "id", "00000000-0000-0000-0000-000000000003",
            "customerNumber", "C00010",
            "totalAmountIncludingTax", 1234.50
        );

        var output = GetInvoice.Output.builder()
            .invoice(invoiceData)
            .build();

        assertThat(output.getInvoice(), notNullValue());
        assertThat(output.getInvoice().get("customerNumber"), is("C00010"));
    }
}
