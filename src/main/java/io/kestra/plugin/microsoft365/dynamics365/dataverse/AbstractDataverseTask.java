package io.kestra.plugin.microsoft365.dynamics365.dataverse;

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
public abstract class AbstractDataverseTask extends AbstractDynamics365Task {

    protected static final String ODATA_VERSION = "4.0";

    @Schema(
        title = "Dataverse Organization URL",
        description = """
            Base URL of the Dataverse organization, e.g. `https://myorg.api.crm.dynamics.com`.
            All OData API calls are made relative to `{orgUrl}/api/data/v9.2/`.
            """
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> orgUrl;

    /**
     * Returns the rendered organization URL stripped of any trailing slash.
     */
    protected String resolvedOrgUrl(RunContext runContext) throws IllegalVariableEvaluationException {
        var raw = runContext.render(this.orgUrl).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("orgUrl is required"));
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    /**
     * Returns the OData v9.2 base URL: `{orgUrl}/api/data/v9.2/`.
     */
    protected String baseUrl(RunContext runContext) throws IllegalVariableEvaluationException {
        return resolvedOrgUrl(runContext) + "/api/data/v9.2/";
    }

    /**
     * Returns the OAuth2 scope for Dataverse: `{orgUrl}/.default`.
     */
    protected String scope(RunContext runContext) throws IllegalVariableEvaluationException {
        return resolvedOrgUrl(runContext) + "/.default";
    }

}
