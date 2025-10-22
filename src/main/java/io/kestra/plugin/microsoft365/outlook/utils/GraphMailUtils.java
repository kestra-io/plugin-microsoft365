package io.kestra.plugin.microsoft365.outlook.utils;

import com.microsoft.graph.models.Attachment;
import com.microsoft.graph.models.Message;
import com.microsoft.graph.models.MessageCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for common Microsoft Graph email operations.
 * Provides reusable methods for fetching messages, attachments, and handling user/me contexts.
 */
public class GraphMailUtils {

    private GraphMailUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Fetches a single message by ID.
     *
     * @param graphClient The Graph service client
     * @param userId User ID (null for current user/me)
     * @param messageId The message ID to fetch
     * @return The message, or null if not found
     */
    public static Message fetchMessage(GraphServiceClient graphClient, String userId, String messageId) {
        return userId != null
            ? graphClient.users().byUserId(userId).messages().byMessageId(messageId).get()
            : graphClient.me().messages().byMessageId(messageId).get();
    }

    /**
     * Fetches attachments for a specific message.
     *
     * @param graphClient The Graph service client
     * @param userId User ID (null for current user/me)
     * @param messageId The message ID
     * @return List of attachments, or empty list if none found
     */
    public static List<Attachment> fetchAttachments(GraphServiceClient graphClient, String userId, String messageId) {
        var attachmentsResponse = userId != null
            ? graphClient.users().byUserId(userId).messages().byMessageId(messageId).attachments().get()
            : graphClient.me().messages().byMessageId(messageId).attachments().get();
        
        return attachmentsResponse != null && attachmentsResponse.getValue() != null 
            ? attachmentsResponse.getValue() 
            : new ArrayList<>();
    }

    /**
     * Fetches messages from a mail folder with optional filtering and ordering.
     *
     * @param graphClient The Graph service client
     * @param userId User ID (null for current user/me)
     * @param folderId The folder ID or well-known name
     * @param filterExpression OData filter expression (optional)
     * @param maxResults Maximum number of results
     * @param logger Logger for debug messages (optional)
     * @return MessageCollectionResponse with messages
     */
    public static MessageCollectionResponse fetchMessages(
        GraphServiceClient graphClient,
        String userId,
        String folderId,
        String filterExpression,
        Integer maxResults,
        Logger logger
    ) {
        var messagesRequest = userId != null
            ? graphClient.users().byUserId(userId).mailFolders().byMailFolderId(folderId).messages()
            : graphClient.me().mailFolders().byMailFolderId(folderId).messages();

        return messagesRequest.get(requestConfig -> {
            if (requestConfig.queryParameters == null) {
                throw new IllegalStateException("Query parameters are null");
            }
            
            if (filterExpression != null) {
                requestConfig.queryParameters.filter = filterExpression;
                if (logger != null) {
                    logger.debug("Applied filter: {}", filterExpression);
                }
            }
            
            requestConfig.queryParameters.top = maxResults;
            requestConfig.queryParameters.orderby = new String[]{"receivedDateTime DESC"};
            requestConfig.queryParameters.select = new String[]{
                "id", "subject", "from", "sender", "receivedDateTime", "sentDateTime",
                "isRead", "hasAttachments", "bodyPreview", "importance", "conversationId"
            };
        });
    }

    /**
     * Fetches messages from a mail folder with optional filtering (without select fields).
     * Used by triggers that need full message data.
     *
     * @param graphClient The Graph service client
     * @param userId User ID (null for current user/me)
     * @param folderId The folder ID or well-known name
     * @param filterExpression OData filter expression (optional)
     * @param maxResults Maximum number of results
     * @return List of messages, or empty list if none found
     */
    public static List<Message> fetchMessagesForTrigger(
        GraphServiceClient graphClient,
        String userId,
        String folderId,
        String filterExpression,
        Integer maxResults
    ) {
        var messagesRequest = userId != null
            ? graphClient.users().byUserId(userId).mailFolders().byMailFolderId(folderId).messages()
            : graphClient.me().mailFolders().byMailFolderId(folderId).messages();

        var response = messagesRequest.get(requestConfig -> {
            if (requestConfig.queryParameters == null) {
                throw new IllegalStateException("Query parameters are null");
            }
            
            if (filterExpression != null) {
                requestConfig.queryParameters.filter = filterExpression;
            }
            
            requestConfig.queryParameters.top = maxResults;
            requestConfig.queryParameters.orderby = new String[]{"receivedDateTime DESC"};
        });

        return response != null && response.getValue() != null ? response.getValue() : new ArrayList<>();
    }
}
