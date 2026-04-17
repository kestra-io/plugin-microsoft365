package io.kestra.plugin.microsoft365.dynamics365.businesscentral;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
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

    @Schema(
        title = "Business Central API endpoint",
        description = """
            Override the Business Central API base URL.
            Useful for sovereign clouds or for testing. Defaults to `https://api.businesscentral.dynamics.com` when not set.
            """
    )
    @PluginProperty(group = "advanced", hidden = true)
    protected Property<String> apiEndpoint;

    private static final String DEFAULT_BC_ENDPOINT = "https://api.businesscentral.dynamics.com";
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    /**
     * Returns the Business Central API v2.0 base URL.
     */
    protected String baseUrl(RunContext runContext) throws IllegalVariableEvaluationException {
        var rTenantId = runContext.render(this.tenantId).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("tenantId is required"));
        var rEnvironment = runContext.render(this.environment).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("environment is required"));
        var rApiEndpoint = runContext.render(this.apiEndpoint).as(String.class).orElse(DEFAULT_BC_ENDPOINT);
        var endpoint = rApiEndpoint.endsWith("/") ? rApiEndpoint.substring(0, rApiEndpoint.length() - 1) : rApiEndpoint;
        return endpoint + "/v2.0/" + rTenantId + "/" + rEnvironment + "/api/v2.0";
    }

    /**
     * Returns the OAuth2 scope for Business Central.
     * When apiEndpoint is overridden, derive the scope from it to allow testing against local stubs.
     */
    protected String scope(RunContext runContext) throws IllegalVariableEvaluationException {
        var rApiEndpoint = runContext.render(this.apiEndpoint).as(String.class).orElse(null);
        if (rApiEndpoint != null) {
            var endpoint = rApiEndpoint.endsWith("/") ? rApiEndpoint.substring(0, rApiEndpoint.length() - 1) : rApiEndpoint;
            return endpoint + "/.default";
        }
        return DEFAULT_BC_ENDPOINT + "/.default";
    }

    protected static void parseAndThrowError(int statusCode, String body) {
        String message = body;
        try {
            var error = MAPPER.readTree(body).path("error");
            var code = error.path("code").asText("");
            var msg = error.path("message").asText(body);
            message = code.isBlank() ? msg : "[" + code + "] " + msg;
        } catch (Exception ignored) {
            // fall back to raw body
        }
        throw new IllegalStateException("Business Central API returned HTTP " + statusCode + ": " + message);
    }
}
