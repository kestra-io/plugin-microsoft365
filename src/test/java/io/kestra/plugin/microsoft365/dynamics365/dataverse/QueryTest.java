package io.kestra.plugin.microsoft365.dynamics365.dataverse;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
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
class QueryTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @Inject
    private RunContextFactory runContextFactory;

    private static final String TENANT_ID = "test-tenant";

    private TestableQuery.TestableQueryBuilder<?, ?> baseTask() {
        return TestableQuery.builder()
            .tenantId(Property.ofValue(TENANT_ID))
            .clientId(Property.ofValue("test-client"))
            .clientSecret(Property.ofValue("test-secret"))
            .orgUrl(Property.ofValue(wm.baseUrl()));
    }

    @Test
    void fetchReturnsAllRecordsFollowingPagination() throws Exception {
        var page1 = ("{\"value\":[{\"accountid\":\"id-1\",\"name\":\"Contoso\"}],"
            + "\"@odata.nextLink\":\"" + wm.baseUrl() + "/api/data/v9.2/accounts?$top=1&$skiptoken=abc\"}");
        var page2 = "{\"value\":[{\"accountid\":\"id-2\",\"name\":\"Fabrikam\"}]}";

        wm.stubFor(get(urlPathEqualTo("/api/data/v9.2/accounts"))
            .withQueryParam("$skiptoken", equalTo("abc"))
            .atPriority(1)
            .willReturn(okJson(page2)));

        wm.stubFor(get(urlPathEqualTo("/api/data/v9.2/accounts"))
            .withQueryParam("$top", equalTo("1"))
            .atPriority(2)
            .willReturn(okJson(page1)));

        var task = baseTask()
            .entitySetName(Property.ofValue("accounts"))
            .top(Property.ofValue(1))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getRecords(), hasSize(2));
        assertThat(output.getSize(), is(2));
        assertThat(output.getRecords().getFirst().get("name"), is("Contoso"));
        assertThat(output.getUri(), nullValue());
    }

    @Test
    void fetchOneReturnsFirstRecordOnly() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/api/data/v9.2/accounts"))
            .withQueryParam("$top", equalTo("1"))
            .willReturn(okJson("{\"value\":[{\"accountid\":\"id-1\",\"name\":\"Contoso\"},{\"accountid\":\"id-2\",\"name\":\"Fabrikam\"}]}")));

        var task = baseTask()
            .entitySetName(Property.ofValue("accounts"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getRecords(), hasSize(1));
        assertThat(output.getSize(), is(1));
        assertThat(output.getRecords().getFirst().get("name"), is("Contoso"));
    }

    @Test
    void storeWritesToInternalStorage() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/api/data/v9.2/contacts"))
            .willReturn(okJson("{\"value\":[{\"contactid\":\"c-1\",\"fullname\":\"Alice\"},{\"contactid\":\"c-2\",\"fullname\":\"Bob\"}]}")));

        var task = baseTask()
            .entitySetName(Property.ofValue("contacts"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getUri(), notNullValue());
        assertThat(output.getSize(), is(2));
        assertThat(output.getRecords(), nullValue());
    }

    @Test
    void throwsWhenBothClientSecretAndCertProvided() {
        var task = Query.builder()
            .tenantId(Property.ofValue(TENANT_ID))
            .clientId(Property.ofValue("test-client"))
            .clientSecret(Property.ofValue("test-secret"))
            .pemCertificate(Property.ofValue("-----BEGIN CERTIFICATE-----"))
            .orgUrl(Property.ofValue(wm.baseUrl()))
            .entitySetName(Property.ofValue("accounts"))
            .build();

        assertThrows(IllegalArgumentException.class,
            () -> task.run(runContextFactory.of(Map.of())));
    }
}
