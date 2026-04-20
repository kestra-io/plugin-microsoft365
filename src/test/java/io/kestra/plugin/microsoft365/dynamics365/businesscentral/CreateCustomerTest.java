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
class CreateCustomerTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @Inject
    private RunContextFactory runContextFactory;

    private static final String TENANT_ID = "test-tenant";
    private static final String COMPANY_ID = "00000000-0000-0000-0000-000000000001";
    private static final String NEW_CUSTOMER_ID = "00000000-0000-0000-0000-000000000099";

    @Test
    void createCustomerReturnsCreatedCustomer() throws Exception {
        wm.stubFor(post(urlPathEqualTo("/v2.0/" + TENANT_ID + "/production/api/v2.0/companies(" + COMPANY_ID + ")/customers"))
            .willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + NEW_CUSTOMER_ID + "\",\"displayName\":\"Acme Corp\",\"email\":\"billing@acme.com\"}")));

        var task = TestableCreateCustomer.builder()
            .tenantId(Property.ofValue(TENANT_ID))
            .clientId(Property.ofValue("test-client"))
            .clientSecret(Property.ofValue("test-secret"))
            .environment(Property.ofValue("production"))
            .apiEndpoint(Property.ofValue(wm.baseUrl()))
            .companyId(Property.ofValue(COMPANY_ID))
            .customer(Property.ofValue(Map.of("displayName", "Acme Corp", "email", "billing@acme.com")))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getCustomerId(), is(NEW_CUSTOMER_ID));
        assertThat(output.getCustomer(), notNullValue());
        assertThat(output.getCustomer().get("displayName"), is("Acme Corp"));
        wm.verify(postRequestedFor(urlPathEqualTo("/v2.0/" + TENANT_ID + "/production/api/v2.0/companies(" + COMPANY_ID + ")/customers")));
    }
}
