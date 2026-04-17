package io.kestra.plugin.microsoft365.dynamics365.dataverse;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class UpsertTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldBuildTaskWithRequiredProperties() {
        var task = Upsert.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .orgUrl(Property.ofValue("https://myorg.api.crm.dynamics.com"))
            .entitySetName(Property.ofValue("accounts"))
            .recordId(Property.ofValue("00000000-0000-0000-0000-000000000001"))
            .record(Property.ofValue(Map.of("name", "Contoso Ltd")))
            .build();

        assertThat(task.getEntitySetName(), notNullValue());
        assertThat(task.getRecordId(), notNullValue());
        assertThat(task.getRecord(), notNullValue());
    }

    @Test
    void shouldBuildOutputWithRecordId() {
        var output = Upsert.Output.builder()
            .recordId("00000000-0000-0000-0000-000000000001")
            .build();

        assertThat(output.getRecordId(), is("00000000-0000-0000-0000-000000000001"));
    }
}
