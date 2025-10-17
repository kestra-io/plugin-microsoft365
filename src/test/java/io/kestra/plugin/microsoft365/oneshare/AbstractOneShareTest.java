package io.kestra.plugin.microsoft365.oneshare;

import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.junit.annotations.KestraTest;
import jakarta.inject.Inject;

@KestraTest(rebuildContext = true)
public abstract class AbstractOneShareTest {
    @Inject
    protected RunContextFactory runContextFactory;

    @Inject
    protected StorageInterface storageInterface;
}
