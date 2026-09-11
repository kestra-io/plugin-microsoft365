package io.kestra.plugin.microsoft365;

import com.microsoft.graph.models.odataerrors.ODataError;
import com.microsoft.kiota.ApiException;

/**
 * Turns a Graph failure into something a user can act on. Shared so the SharePoint and OneDrive downloads report the
 * same way for the same status.
 */
public final class GraphDownloadErrors {
    private GraphDownloadErrors() {
    }

    public static RuntimeException of(ApiException e, String itemRef, String driveId) {
        return switch (e.getResponseStatusCode()) {
            case 404 -> new IllegalArgumentException(
                "Item '%s' not found in drive '%s'. The file may not exist or the ID is incorrect".formatted(itemRef, driveId), e
            );
            case 403 -> new IllegalStateException(
                "Permission denied. Insufficient permissions to download item '%s' from drive '%s'".formatted(itemRef, driveId), e
            );
            case 401 -> new IllegalStateException(
                "Authentication failed. Please verify your credentials (tenantId, clientId, clientSecret)", e
            );
            case 429 -> new IllegalStateException(
                "Rate limit exceeded. Too many requests to Microsoft Graph API. Please retry after some time", e
            );
            case 416 -> new IllegalStateException(
                "Invalid range request for item '%s'. The requested byte range is not satisfiable".formatted(itemRef), e
            );
            case 503, 504 -> new IllegalStateException(
                "Microsoft Graph API is temporarily unavailable. Please retry after some time", e
            );
            default -> new RuntimeException(
                "Failed to download item '%s' from drive '%s': %s".formatted(itemRef, driveId, message(e)), e
            );
        };
    }

    /** An ODataError carries Graph's own explanation, which is more useful than the generic exception message. */
    private static String message(ApiException e) {
        if (e instanceof ODataError oDataError && oDataError.getError() != null) {
            return oDataError.getError().getMessage();
        }

        return e.getMessage();
    }
}
