package io.kestra.plugin.microsoft365.dynamics365.dataverse;

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
class DeleteTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @Inject
    private RunContextFactory runContextFactory;

    private static final String TENANT_ID = "test-tenant";
    private static final String RECORD_ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void deleteSucceeds() throws Exception {
        wm.stubFor(delete(urlPathEqualTo("/api/data/v9.2/contacts(" + RECORD_ID + ")"))
            .willReturn(aResponse().withStatus(204)));

        var task = TestableDelete.builder()
            .tenantId(Property.ofValue(TENANT_ID))
            .clientId(Property.ofValue("test-client"))
            .clientSecret(Property.ofValue("test-secret"))
            .orgUrl(Property.ofValue(wm.baseUrl()))
            .entitySetName(Property.ofValue("contacts"))
            .recordId(Property.ofValue(RECORD_ID))
            .build();

        task.run(runContextFactory.of(Map.of()));

        wm.verify(deleteRequestedFor(urlPathEqualTo("/api/data/v9.2/contacts(" + RECORD_ID + ")")));
    }

    @Test
    void deleteThrowsOnApiError() throws Exception {
        wm.stubFor(delete(urlPathEqualTo("/api/data/v9.2/contacts(" + RECORD_ID + ")"))
            .willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"0x80040217\",\"message\":\"Record not found\"}}")));

        var task = TestableDelete.builder()
            .tenantId(Property.ofValue(TENANT_ID))
            .clientId(Property.ofValue("test-client"))
            .clientSecret(Property.ofValue("test-secret"))
            .orgUrl(Property.ofValue(wm.baseUrl()))
            .entitySetName(Property.ofValue("contacts"))
            .recordId(Property.ofValue(RECORD_ID))
            .build();

        var ex = assertThrows(IllegalStateException.class,
            () -> task.run(runContextFactory.of(Map.of())));
        assertThat(ex.getMessage(), containsString("0x80040217"));
    }
}
