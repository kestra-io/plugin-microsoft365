package io.kestra.plugin.microsoft365.oneshare;

import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.junit.annotations.KestraTest;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;

@KestraTest(rebuildContext = true)
public abstract class AbstractOneShareTest {
    @Inject
    protected RunContextFactory runContextFactory;

    @Inject
    protected StorageInterface storageInterface;

    @Value("${kestra.tasks.oneshare.tenantId}")
    protected String tenantId;

    @Value("${kestra.tasks.oneshare.clientId}")
    protected String clientId;

    @Value("${kestra.tasks.oneshare.clientSecret}")
    protected String clientSecret;

    @Value("${kestra.tasks.oneshare.driveId}")
    protected String driveId;

    protected boolean credentialsAvailable;

    @BeforeEach
    void checkCredentials() {
        credentialsAvailable = StringUtils.isNotBlank(tenantId) &&
                               StringUtils.isNotBlank(clientId) &&
                               StringUtils.isNotBlank(clientSecret) &&
                               StringUtils.isNotBlank(driveId);
    }

    /**
     * Condition method for @EnabledIf to enable integration tests only when credentials are available.
     * This allows integration tests to be skipped automatically when Microsoft 365 credentials are not configured.
     * 
     * @return true if all required credentials are configured, false otherwise
     */
    protected static boolean isIntegrationTestEnabled() {
        String tenantId = System.getProperty("kestra.tasks.oneshare.tenantId", System.getenv("KESTRA_TASKS_ONESHARE_TENANTID"));
        String clientId = System.getProperty("kestra.tasks.oneshare.clientId", System.getenv("KESTRA_TASKS_ONESHARE_CLIENTID"));
        String clientSecret = System.getProperty("kestra.tasks.oneshare.clientSecret", System.getenv("KESTRA_TASKS_ONESHARE_CLIENTSECRET"));
        String driveId = System.getProperty("kestra.tasks.oneshare.driveId", System.getenv("KESTRA_TASKS_ONESHARE_DRIVEID"));
        
        return StringUtils.isNotBlank(tenantId) &&
               StringUtils.isNotBlank(clientId) &&
               StringUtils.isNotBlank(clientSecret) &&
               StringUtils.isNotBlank(driveId);
    }
}
