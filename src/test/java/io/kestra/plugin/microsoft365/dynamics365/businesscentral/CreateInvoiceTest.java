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
class CreateInvoiceTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldBuildTaskWithRequiredProperties() {
        var task = CreateInvoice.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .environment(Property.ofValue("production"))
            .companyId(Property.ofValue("00000000-0000-0000-0000-000000000001"))
            .invoice(Property.ofValue(Map.of("customerNumber", "C00010")))
            .build();

        assertThat(task.getCompanyId(), notNullValue());
        assertThat(task.getInvoice(), notNullValue());
    }

    @Test
    void shouldBuildOutputWithInvoiceIdAndRecord() {
        var invoiceData = Map.<String, Object>of(
            "id", "00000000-0000-0000-0000-000000000088",
            "customerNumber", "C00010"
        );

        var output = CreateInvoice.Output.builder()
            .invoiceId("00000000-0000-0000-0000-000000000088")
            .invoice(invoiceData)
            .build();

        assertThat(output.getInvoiceId(), is("00000000-0000-0000-0000-000000000088"));
        assertThat(output.getInvoice().get("customerNumber"), is("C00010"));
    }
}
