package io.kestra.plugin.microsoft365.dynamics365.businesscentral;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.microsoft365.dynamics365.AbstractDynamics365Task;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractBusinessCentralTask extends AbstractDynamics365Task {

    @Schema(
        title = "Business Central Environment Name",
        description = """
            Name of the Business Central environment to target, e.g. `production` or `sandbox`.
            This is combined with the tenant ID to form the base URL:
            `https://api.businesscentral.dynamics.com/v2.0/{tenantId}/{environment}/api/v2.0`.
            """
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> environment;

    /**
     * Returns the Business Central API v2.0 base URL.
     */
    protected String baseUrl(RunContext runContext) throws IllegalVariableEvaluationException {
        var rTenantId = runContext.render(this.tenantId).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("tenantId is required"));
        var rEnvironment = runContext.render(this.environment).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("environment is required"));
        return "https://api.businesscentral.dynamics.com/v2.0/" + rTenantId + "/" + rEnvironment + "/api/v2.0";
    }

    /**
     * Returns the fixed OAuth2 scope for Business Central.
     */
    protected String scope() {
        return "https://api.businesscentral.dynamics.com/.default";
    }
}
