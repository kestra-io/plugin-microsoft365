
package io.kestra.plugin.microsoft365.outlook;

import com.microsoft.graph.models.Message;
import com.microsoft.graph.models.MessageCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.microsoft365.outlook.utils.GraphMailUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;

import io.kestra.core.junit.annotations.KestraTest;
import jakarta.inject.Inject;

@KestraTest
public class ListTest {

    @Mock
    private GraphServiceClient graphServiceClient;

    @Inject
    private RunContextFactory runContextFactory;

    private List list;

    @BeforeEach
    void setUp() {
        list = List.builder()
            .id("list-task-" + IdUtils.create())
            .type(List.class.getName())
            .tenantId(Property.ofValue("test"))
            .clientId(Property.ofValue("test"))
            .clientSecret(Property.ofValue("test"))
            .build();
    }

    @Test
    void run() throws Exception {
        List spy = Mockito.spy(list);
        Mockito.doReturn(graphServiceClient).when(spy).createGraphClient(any());

        try (MockedStatic<GraphMailUtils> utilities = Mockito.mockStatic(GraphMailUtils.class)) {
            RunContext runContext = runContextFactory.of();

            MessageCollectionResponse messageCollectionResponse = new MessageCollectionResponse();
            Message message = new Message();
            message.setId("123");
            message.setSubject("Test Subject");
            messageCollectionResponse.setValue(Collections.singletonList(message));

            utilities.when(() -> GraphMailUtils.fetchMessages(any(), any(), any(), any(), any(), any())).thenReturn(messageCollectionResponse);

            spy = List.builder()
                .id("list-task-" + IdUtils.create())
                .type(List.class.getName())
                .tenantId(Property.ofValue("test"))
                .clientId(Property.ofValue("test"))
                .clientSecret(Property.ofValue("test"))
                .fetchType(Property.ofValue(FetchType.FETCH))
                .build();

            List.Output output = spy.run(runContext);

            assertThat(output.getCount(), is(1));
            assertThat(output.getMessages().get(0).getId(), is("123"));
        }
    }
}
