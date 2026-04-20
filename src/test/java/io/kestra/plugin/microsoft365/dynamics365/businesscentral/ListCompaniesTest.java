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
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ListCompaniesTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @Inject
    private RunContextFactory runContextFactory;

    private static final String TENANT_ID = "test-tenant";

    @Test
    void listCompaniesReturnsCompanies() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/v2.0/" + TENANT_ID + "/production/api/v2.0/companies"))
            .willReturn(okJson("{\"value\":[{\"id\":\"company-1\",\"name\":\"Cronus International Ltd.\"},{\"id\":\"company-2\",\"name\":\"CRONUS USA Inc.\"}]}")));

        var task = TestableListCompanies.builder()
            .tenantId(Property.ofValue(TENANT_ID))
            .clientId(Property.ofValue("test-client"))
            .clientSecret(Property.ofValue("test-secret"))
            .environment(Property.ofValue("production"))
            .apiEndpoint(Property.ofValue(wm.baseUrl()))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getCompanies(), hasSize(2));
        assertThat(output.getSize(), is(2));
        assertThat(output.getCompanies().getFirst().get("name"), is("Cronus International Ltd."));
    }

    @Test
    void listCompaniesThrowsOnApiError() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/v2.0/" + TENANT_ID + "/production/api/v2.0/companies"))
            .willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"Unauthorized\",\"message\":\"Invalid credentials\"}}")));

        var task = TestableListCompanies.builder()
            .tenantId(Property.ofValue(TENANT_ID))
            .clientId(Property.ofValue("test-client"))
            .clientSecret(Property.ofValue("test-secret"))
            .environment(Property.ofValue("production"))
            .apiEndpoint(Property.ofValue(wm.baseUrl()))
            .build();

        var ex = assertThrows(IllegalStateException.class,
            () -> task.run(runContextFactory.of(Map.of())));
        assertThat(ex.getMessage(), containsString("Unauthorized"));
    }
}
