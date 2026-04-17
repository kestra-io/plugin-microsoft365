package io.kestra.plugin.microsoft365.dynamics365.businesscentral;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class ListItemsTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldBuildTaskWithRequiredProperties() {
        var task = ListItems.builder()
            .tenantId(Property.ofValue("test-tenant-id"))
            .clientId(Property.ofValue("test-client-id"))
            .clientSecret(Property.ofValue("test-client-secret"))
            .environment(Property.ofValue("production"))
            .companyId(Property.ofValue("00000000-0000-0000-0000-000000000001"))
            .filter(Property.ofValue("type eq 'Inventory'"))
            .top(Property.ofValue(50))
            .build();

        assertThat(task.getCompanyId(), notNullValue());
        assertThat(task.getEnvironment(), notNullValue());
    }

    @Test
    void shouldBuildOutputWithItems() {
        var items = List.of(
            Map.<String, Object>of("id", "item-1", "displayName", "Widget A", "type", "Inventory"),
            Map.<String, Object>of("id", "item-2", "displayName", "Service B", "type", "Service")
        );

        var output = ListItems.Output.builder()
            .items(items)
            .size(items.size())
            .build();

        assertThat(output.getItems(), hasSize(2));
        assertThat(output.getSize(), is(2));
        assertThat(output.getItems().getFirst().get("displayName"), is("Widget A"));
    }
}
