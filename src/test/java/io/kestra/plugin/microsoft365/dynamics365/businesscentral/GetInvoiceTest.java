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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

@KestraTest
class GetInvoiceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @Inject
    private RunContextFactory runContextFactory;

    private static final String TENANT_ID = "test-tenant";
    private static final String COMPANY_ID = "00000000-0000-0000-0000-000000000001";
    private static final String INVOICE_ID = "00000000-0000-0000-0000-000000000003";

    @Test
    void getInvoiceReturnsInvoice() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/v2.0/" + TENANT_ID + "/production/api/v2.0/companies(" + COMPANY_ID + ")/salesInvoices(" + INVOICE_ID + ")"))
            .willReturn(okJson("{\"id\":\"" + INVOICE_ID + "\",\"number\":\"INV-001\",\"customerName\":\"Acme Corp\",\"totalAmountIncludingTax\":1500.00}")));

        var task = spy(GetInvoice.builder()
            .tenantId(Property.ofValue(TENANT_ID))
            .clientId(Property.ofValue("test-client"))
            .clientSecret(Property.ofValue("test-secret"))
            .environment(Property.ofValue("production"))
            .apiEndpoint(Property.ofValue(wm.baseUrl()))
            .companyId(Property.ofValue(COMPANY_ID))
            .invoiceId(Property.ofValue(INVOICE_ID))
            .build());
        doReturn("fake-token").when(task).getAccessToken(any(), anyString());

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getInvoice(), notNullValue());
        assertThat(output.getInvoice().get("number"), is("INV-001"));
        assertThat(output.getInvoice().get("id"), is(INVOICE_ID));
    }
}
