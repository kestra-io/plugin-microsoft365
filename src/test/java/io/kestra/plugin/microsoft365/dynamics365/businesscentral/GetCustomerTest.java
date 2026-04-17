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
class GetCustomerTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @Inject
    private RunContextFactory runContextFactory;

    private static final String TENANT_ID = "test-tenant";
    private static final String COMPANY_ID = "00000000-0000-0000-0000-000000000001";
    private static final String CUSTOMER_ID = "00000000-0000-0000-0000-000000000002";

    @Test
    void getCustomerReturnsCustomer() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/v2.0/" + TENANT_ID + "/production/api/v2.0/companies(" + COMPANY_ID + ")/customers(" + CUSTOMER_ID + ")"))
            .willReturn(okJson("{\"id\":\"" + CUSTOMER_ID + "\",\"displayName\":\"Acme Corp\",\"email\":\"billing@acme.com\"}")));

        var task = spy(GetCustomer.builder()
            .tenantId(Property.ofValue(TENANT_ID))
            .clientId(Property.ofValue("test-client"))
            .clientSecret(Property.ofValue("test-secret"))
            .environment(Property.ofValue("production"))
            .apiEndpoint(Property.ofValue(wm.baseUrl()))
            .companyId(Property.ofValue(COMPANY_ID))
            .customerId(Property.ofValue(CUSTOMER_ID))
            .build());
        doReturn("fake-token").when(task).getAccessToken(any(), anyString());

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getCustomer(), notNullValue());
        assertThat(output.getCustomer().get("displayName"), is("Acme Corp"));
        assertThat(output.getCustomer().get("id"), is(CUSTOMER_ID));
    }
}
