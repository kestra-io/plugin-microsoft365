package io.kestra.plugin.microsoft365.teams.adaptivecards;

import com.microsoft.graph.models.ChatMessage;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.teams.TeamsRequestBuilder;
import com.microsoft.graph.teams.item.TeamItemRequestBuilder;
import com.microsoft.graph.teams.item.channels.ChannelsRequestBuilder;
import com.microsoft.graph.teams.item.channels.item.ChannelItemRequestBuilder;
import com.microsoft.graph.teams.item.channels.item.messages.MessagesRequestBuilder;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.OffsetDateTime;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class SendTest {

    @Inject
    private RunContextFactory runContextFactory;

    private static final String CARD_JSON = """
        {"type": "AdaptiveCard", "version": "1.4", "body": []}""";

    @Test
    void sendCardToChannel() throws Exception {
        RunContext runContext = runContextFactory.of();

        var graphClient = mock(GraphServiceClient.class);
        var teamsBuilder = mock(TeamsRequestBuilder.class);
        var teamItemBuilder = mock(TeamItemRequestBuilder.class);
        var channelsBuilder = mock(ChannelsRequestBuilder.class);
        var channelItemBuilder = mock(ChannelItemRequestBuilder.class);
        var messagesBuilder = mock(MessagesRequestBuilder.class);

        var returnedMessage = new ChatMessage();
        returnedMessage.setId("19:message-id@thread.tacv2");
        returnedMessage.setCreatedDateTime(OffsetDateTime.parse("2026-07-23T10:15:30Z"));

        when(graphClient.teams()).thenReturn(teamsBuilder);
        when(teamsBuilder.byTeamId(anyString())).thenReturn(teamItemBuilder);
        when(teamItemBuilder.channels()).thenReturn(channelsBuilder);
        when(channelsBuilder.byChannelId(anyString())).thenReturn(channelItemBuilder);
        when(channelItemBuilder.messages()).thenReturn(messagesBuilder);
        when(messagesBuilder.post(any(ChatMessage.class))).thenReturn(returnedMessage);

        var task = Send.builder()
            .tenantId(Property.ofValue("mock-tenant-id"))
            .clientId(Property.ofValue("mock-client-id"))
            .clientSecret(Property.ofValue("mock-client-secret"))
            .teamId(Property.ofValue("team-1"))
            .channelId(Property.ofValue("channel-1"))
            .card(Property.ofValue(CARD_JSON))
            .build();
        var taskSpy = Mockito.spy(task);
        Mockito.doReturn(graphClient).when(taskSpy).graphClient(any());

        var output = taskSpy.run(runContext);

        var messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messagesBuilder, times(1)).post(messageCaptor.capture());

        var postedMessage = messageCaptor.getValue();
        assertThat(postedMessage.getAttachments(), hasSize(1));

        var attachment = postedMessage.getAttachments().getFirst();
        assertThat(attachment.getContentType(), is("application/vnd.microsoft.card.adaptive"));
        assertThat(attachment.getContent(), is(CARD_JSON));
        assertThat(postedMessage.getBody().getContent(), containsString(attachment.getId()));

        assertThat(output, is(notNullValue()));
        assertThat(output.getMessageId(), is("19:message-id@thread.tacv2"));
        assertThat(output.getCreatedDateTime(), is(returnedMessage.getCreatedDateTime().toString()));

        verify(teamsBuilder, times(1)).byTeamId("team-1");
        verify(channelsBuilder, times(1)).byChannelId("channel-1");
    }

    @Test
    void invalidCardJsonThrows() {
        var runContext = runContextFactory.of();

        var task = Send.builder()
            .tenantId(Property.ofValue("mock-tenant-id"))
            .clientId(Property.ofValue("mock-client-id"))
            .clientSecret(Property.ofValue("mock-client-secret"))
            .teamId(Property.ofValue("team-1"))
            .channelId(Property.ofValue("channel-1"))
            .card(Property.ofValue("not-json"))
            .build();

        assertThrows(Exception.class, () -> task.run(runContext));
    }
}
