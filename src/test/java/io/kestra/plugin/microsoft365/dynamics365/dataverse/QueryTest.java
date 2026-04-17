package io.kestra.plugin.microsoft365.dynamics365.dataverse;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class QueryTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldBuildTaskWithRequiredProperties() {
        var task = Query.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .orgUrl(Property.ofValue("https://myorg.api.crm.dynamics.com"))
            .entitySetName(Property.ofValue("accounts"))
            .filter(Property.ofValue("statecode eq 0"))
            .select(Property.ofValue("accountid,name"))
            .top(Property.ofValue(50))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        assertThat(task.getEntitySetName(), notNullValue());
        assertThat(task.getOrgUrl(), notNullValue());
        assertThat(task.getTenantId(), notNullValue());
        assertThat(task.getClientId(), notNullValue());
        assertThat(task.getClientSecret(), notNullValue());
    }

    @Test
    void shouldBuildFetchOutput() {
        var records = List.of(
            Map.<String, Object>of("accountid", "id-1", "name", "Contoso"),
            Map.<String, Object>of("accountid", "id-2", "name", "Fabrikam")
        );

        var output = Query.Output.builder()
            .records(records)
            .size(records.size())
            .build();

        assertThat(output.getRecords(), hasSize(2));
        assertThat(output.getSize(), is(2));
        assertThat(output.getRecords().getFirst().get("name"), is("Contoso"));
    }

    @Test
    void shouldBuildStoreOutput() {
        var output = Query.Output.builder()
            .uri(java.net.URI.create("kestra:///test/file.ion"))
            .size(100)
            .build();

        assertThat(output.getUri(), notNullValue());
        assertThat(output.getSize(), is(100));
        assertThat(output.getRecords(), nullValue());
    }
}
