package io.kestra.plugin.microsoft365.teams.adaptivecards;

import com.microsoft.graph.chats.ChatsRequestBuilder;
import com.microsoft.graph.chats.item.ChatItemRequestBuilder;
import com.microsoft.graph.chats.item.messages.MessagesRequestBuilder;
import com.microsoft.graph.models.AadUserConversationMember;
import com.microsoft.graph.models.Chat;
import com.microsoft.graph.models.ChatMessage;
import com.microsoft.graph.models.ChatType;
import com.microsoft.graph.models.ConversationMember;
import com.microsoft.graph.models.User;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.users.item.UserItemRequestBuilder;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

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
class SendToUserTest {

    @Inject
    private RunContextFactory runContextFactory;

    private static final String CARD_JSON = """
        {"type": "AdaptiveCard", "version": "1.4", "body": []}""";

    @Test
    void sendCardToUserByEmail() throws Exception {
        var runContext = runContextFactory.of();

        var graphClient = mock(GraphServiceClient.class);
        var chatsBuilder = mock(ChatsRequestBuilder.class);
        var chatItemBuilder = mock(ChatItemRequestBuilder.class);
        var messagesBuilder = mock(MessagesRequestBuilder.class);
        var meBuilder = mock(UserItemRequestBuilder.class);

        var createdChat = new Chat();
        createdChat.setId("19:chat-id@unq.gbl.spaces");

        var createdMessage = new ChatMessage();
        createdMessage.setId("msg-id");

        var me = new User();
        me.setId("caller-id");

        when(graphClient.me()).thenReturn(meBuilder);
        when(meBuilder.get(any())).thenReturn(me);
        when(graphClient.chats()).thenReturn(chatsBuilder);
        when(chatsBuilder.post(any(Chat.class))).thenReturn(createdChat);
        when(chatsBuilder.byChatId(anyString())).thenReturn(chatItemBuilder);
        when(chatItemBuilder.messages()).thenReturn(messagesBuilder);
        when(messagesBuilder.post(any(ChatMessage.class))).thenReturn(createdMessage);

        var task = SendToUser.builder()
            .tenantId(Property.ofValue("mock-tenant-id"))
            .clientId(Property.ofValue("mock-client-id"))
            .clientSecret(Property.ofValue("mock-client-secret"))
            .userEmail(Property.ofValue("oncall@company.com"))
            .card(Property.ofValue(CARD_JSON))
            .build();
        var taskSpy = Mockito.spy(task);
        Mockito.doReturn(graphClient).when(taskSpy).graphClient(any());

        var output = taskSpy.run(runContext);

        var chatCaptor = ArgumentCaptor.forClass(Chat.class);
        verify(chatsBuilder, times(1)).post(chatCaptor.capture());
        var postedChat = chatCaptor.getValue();
        assertThat(postedChat.getChatType(), is(ChatType.OneOnOne));
        assertThat(postedChat.getMembers(), hasSize(2));
        assertThat(odataBind(postedChat.getMembers().get(0)), is("https://graph.microsoft.com/v1.0/users('oncall@company.com')"));
        assertThat(odataBind(postedChat.getMembers().get(1)), is("https://graph.microsoft.com/v1.0/users('caller-id')"));

        verify(chatsBuilder, times(1)).byChatId("19:chat-id@unq.gbl.spaces");

        var messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messagesBuilder, times(1)).post(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getAttachments(), hasSize(1));

        assertThat(output, is(notNullValue()));
        assertThat(output.getChatId(), is("19:chat-id@unq.gbl.spaces"));
        assertThat(output.getMessageId(), is("msg-id"));
    }

    @Test
    void sendCardToUserByIdWithQuoteEscapesODataBind() throws Exception {
        var runContext = runContextFactory.of();

        var graphClient = mock(GraphServiceClient.class);
        var chatsBuilder = mock(ChatsRequestBuilder.class);
        var chatItemBuilder = mock(ChatItemRequestBuilder.class);
        var messagesBuilder = mock(MessagesRequestBuilder.class);
        var meBuilder = mock(UserItemRequestBuilder.class);

        var createdChat = new Chat();
        createdChat.setId("19:chat-id@unq.gbl.spaces");

        var createdMessage = new ChatMessage();
        createdMessage.setId("msg-id");

        var me = new User();
        me.setId("caller'id");

        when(graphClient.me()).thenReturn(meBuilder);
        when(meBuilder.get(any())).thenReturn(me);
        when(graphClient.chats()).thenReturn(chatsBuilder);
        when(chatsBuilder.post(any(Chat.class))).thenReturn(createdChat);
        when(chatsBuilder.byChatId(anyString())).thenReturn(chatItemBuilder);
        when(chatItemBuilder.messages()).thenReturn(messagesBuilder);
        when(messagesBuilder.post(any(ChatMessage.class))).thenReturn(createdMessage);

        var task = SendToUser.builder()
            .tenantId(Property.ofValue("mock-tenant-id"))
            .clientId(Property.ofValue("mock-client-id"))
            .clientSecret(Property.ofValue("mock-client-secret"))
            .userId(Property.ofValue("target'id"))
            .card(Property.ofValue(CARD_JSON))
            .build();
        var taskSpy = Mockito.spy(task);
        Mockito.doReturn(graphClient).when(taskSpy).graphClient(any());

        taskSpy.run(runContext);

        var chatCaptor = ArgumentCaptor.forClass(Chat.class);
        verify(chatsBuilder, times(1)).post(chatCaptor.capture());
        var postedChat = chatCaptor.getValue();
        assertThat(odataBind(postedChat.getMembers().get(0)), is("https://graph.microsoft.com/v1.0/users('target''id')"));
        assertThat(odataBind(postedChat.getMembers().get(1)), is("https://graph.microsoft.com/v1.0/users('caller''id')"));
    }

    private static String odataBind(ConversationMember member) {
        return (String) ((AadUserConversationMember) member).getAdditionalData().get("user@odata.bind");
    }

    @Test
    void neitherUserIdNorEmailThrows() {
        var runContext = runContextFactory.of();
        var task = SendToUser.builder()
            .tenantId(Property.ofValue("mock-tenant-id"))
            .clientId(Property.ofValue("mock-client-id"))
            .clientSecret(Property.ofValue("mock-client-secret"))
            .card(Property.ofValue(CARD_JSON))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("Exactly one"));
    }

    @Test
    void bothUserIdAndEmailThrows() {
        var runContext = runContextFactory.of();
        var task = SendToUser.builder()
            .tenantId(Property.ofValue("mock-tenant-id"))
            .clientId(Property.ofValue("mock-client-id"))
            .clientSecret(Property.ofValue("mock-client-secret"))
            .userId(Property.ofValue("user-1"))
            .userEmail(Property.ofValue("oncall@company.com"))
            .card(Property.ofValue(CARD_JSON))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("Exactly one"));
    }
}
