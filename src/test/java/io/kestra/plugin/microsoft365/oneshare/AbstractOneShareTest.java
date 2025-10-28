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
}
