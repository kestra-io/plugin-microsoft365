
package io.kestra.plugin.microsoft365.outlook;

import com.microsoft.graph.models.ItemBody;
import com.microsoft.graph.models.Message;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.microsoft365.outlook.utils.GraphMailUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;

import io.kestra.core.junit.annotations.KestraTest;
import jakarta.inject.Inject;

@KestraTest
public class GetTest {

    @Mock
    private GraphServiceClient graphServiceClient;

    @Inject
    private RunContextFactory runContextFactory;

    private Get get;

    @BeforeEach
    void setUp() {
        get = Get.builder()
            .id("get-task")
            .type(Get.class.getName())
            .tenantId(Property.ofValue("test"))
            .clientId(Property.ofValue("test"))
            .clientSecret(Property.ofValue("test"))
            .messageID(Property.ofValue("123"))
            .build();
    }

    @Test
    void run() throws Exception {
        Get spy = Mockito.spy(get);
        Mockito.doReturn(graphServiceClient).when(spy).createGraphClient(any());

        RunContext runContext = runContextFactory.of();

        Message message = new Message();
        message.setId("123");
        message.setSubject("Test Subject");
        ItemBody body = new ItemBody();
        body.setContent("Test Body");
        message.setBody(body);

        try (MockedStatic<GraphMailUtils> utilities = Mockito.mockStatic(GraphMailUtils.class)) {
            utilities.when(() -> GraphMailUtils.fetchMessage(any(), any(), any())).thenReturn(message);

            Get.Output output = spy.run(runContext);

            assertThat(output.getMessage().getId(), is("123"));
            assertThat(output.getMessage().getSubject(), is("Test Subject"));
        }
    }
}
