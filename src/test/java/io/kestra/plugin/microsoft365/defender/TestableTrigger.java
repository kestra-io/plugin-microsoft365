package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.runners.RunContext;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@NoArgsConstructor
public class TestableTrigger extends Trigger {
    @Override
    protected GraphServiceClient graphClient(RunContext runContext) {
        return AbstractDefenderTest.wireMockClient();
    }
}
