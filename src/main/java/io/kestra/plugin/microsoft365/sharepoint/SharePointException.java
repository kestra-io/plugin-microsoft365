package io.kestra.plugin.microsoft365.sharepoint;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;

/**
 * Custom exception class for SharePoint operations.
 */
public class SharePointException extends IllegalVariableEvaluationException {
    public SharePointException(String message) {
        super(message);
    }

    public SharePointException(String message, Throwable cause) {
        super(message, cause);
    }
}
