package io.kestra.plugin.microsoft365.defender;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.authentication.AnonymousAuthenticationProvider;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * Runs the tasks against WireMock through the real Graph SDK client (no token call), so tests check the actual
 * request URLs, query strings and bodies, and the parsing of the Graph JSON responses.
 */
abstract class AbstractDefenderTest {
    static final String GRAPH = "/v1.0";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @Inject
    protected RunContextFactory runContextFactory;

    static GraphServiceClient wireMockClient() {
        GraphServiceClient client = new GraphServiceClient(new AnonymousAuthenticationProvider());
        client.getRequestAdapter().setBaseUrl(wm.baseUrl() + GRAPH);
        return client;
    }

    static <T extends AbstractDefender> T withWireMock(T task) throws Exception {
        T spied = spy(task);
        doReturn(wireMockClient()).when(spied).graphClient(any());
        return spied;
    }

    RunContext runContext() {
        return runContextFactory.of(Map.of());
    }

    static String alertJson(String id, String severity, String status, String lastUpdate) {
        return """
            {"@odata.type":"#microsoft.graph.security.alert","id":"%s","title":"Suspicious PowerShell","severity":"%s","status":"%s",
             "serviceSource":"microsoftDefenderForEndpoint","lastUpdateDateTime":"%s",
             "evidence":[{"@odata.type":"#microsoft.graph.security.deviceEvidence","deviceDnsName":"host-1"}]}"""
            .formatted(id, severity, status, lastUpdate);
    }

    static String incidentJson(String id, String status, String lastUpdate) {
        return """
            {"id":"%s","displayName":"Multi-stage incident","severity":"high","status":"%s","lastUpdateDateTime":"%s","customTags":["existing"]}"""
            .formatted(id, status, lastUpdate);
    }
}
