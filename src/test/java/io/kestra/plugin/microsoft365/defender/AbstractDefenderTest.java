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

// Real Graph SDK client against WireMock (no token call), so tests check the actual requests and the parsing of the responses
abstract class AbstractDefenderTest {
    static final String GRAPH = "/v1.0";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @Inject
    protected RunContextFactory runContextFactory;

    static GraphServiceClient wireMockClient() {
        var client = new GraphServiceClient(new AnonymousAuthenticationProvider());
        client.getRequestAdapter().setBaseUrl(wm.baseUrl() + GRAPH);
        return client;
    }

    static <T extends AbstractDefender> T withWireMock(T task) throws Exception {
        var spied = spy(task);
        doReturn(wireMockClient()).when(spied).graphClient(any());
        return spied;
    }

    RunContext runContext() {
        return runContextFactory.of(Map.of());
    }

    static String alertJson(String id, String severity, String status, String lastUpdate) {
        return alertJson(id, severity, status, lastUpdate, lastUpdate);
    }

    static String alertJson(String id, String severity, String status, String created, String lastUpdate) {
        return """
            {"@odata.type":"#microsoft.graph.security.alert","id":"%s","title":"Suspicious PowerShell","severity":"%s","status":"%s",
             "serviceSource":"microsoftDefenderForEndpoint","createdDateTime":"%s","lastUpdateDateTime":"%s",
             "evidence":[{"@odata.type":"#microsoft.graph.security.deviceEvidence","deviceDnsName":"host-1"}]}"""
            .formatted(id, severity, status, created, lastUpdate);
    }

    static String incidentJson(String id, String status, String lastUpdate) {
        return """
            {"id":"%s","displayName":"Multi-stage incident","severity":"high","status":"%s","createdDateTime":"%s","lastUpdateDateTime":"%s","customTags":["existing"]}"""
            .formatted(id, status, lastUpdate, lastUpdate);
    }
}
