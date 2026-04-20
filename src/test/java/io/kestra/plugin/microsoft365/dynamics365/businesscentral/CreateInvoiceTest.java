package io.kestra.plugin.microsoft365.dynamics365.businesscentral;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class CreateInvoiceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @Inject
    private RunContextFactory runContextFactory;

    private static final String TENANT_ID = "test-tenant";
    private static final String COMPANY_ID = "00000000-0000-0000-0000-000000000001";
    private static final String NEW_INVOICE_ID = "00000000-0000-0000-0000-000000000088";

    @Test
    void createInvoiceReturnsCreatedInvoice() throws Exception {
        wm.stubFor(post(urlPathEqualTo("/v2.0/" + TENANT_ID + "/production/api/v2.0/companies(" + COMPANY_ID + ")/salesInvoices"))
            .willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + NEW_INVOICE_ID + "\",\"number\":\"INV-002\",\"customerNumber\":\"C00010\",\"invoiceDate\":\"2026-04-17\"}")));

        var task = TestableCreateInvoice.builder()
            .tenantId(Property.ofValue(TENANT_ID))
            .clientId(Property.ofValue("test-client"))
            .clientSecret(Property.ofValue("test-secret"))
            .environment(Property.ofValue("production"))
            .apiEndpoint(Property.ofValue(wm.baseUrl()))
            .companyId(Property.ofValue(COMPANY_ID))
            .invoice(Property.ofValue(Map.of("customerNumber", "C00010", "invoiceDate", "2026-04-17")))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getInvoiceId(), is(NEW_INVOICE_ID));
        assertThat(output.getInvoice(), notNullValue());
        assertThat(output.getInvoice().get("number"), is("INV-002"));
        wm.verify(postRequestedFor(urlPathEqualTo("/v2.0/" + TENANT_ID + "/production/api/v2.0/companies(" + COMPANY_ID + ")/salesInvoices")));
    }
}
