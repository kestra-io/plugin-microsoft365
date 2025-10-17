package io.kestra.plugin.microsoft365.oneshare;

import com.devskiller.friendly_id.FriendlyId;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.queues.QueueFactoryInterface;
import io.kestra.core.queues.QueueInterface;
import io.kestra.core.repositories.LocalFlowRepositoryLoader;
import io.kestra.core.runners.FlowListeners;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.jdbc.runner.JdbcScheduler;
import io.kestra.plugin.microsoft365.oneshare.models.OneShareFile;
import io.kestra.scheduler.AbstractScheduler;
import io.kestra.worker.DefaultWorker;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.kestra.core.tenant.TenantService.MAIN_TENANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
class TriggerTest extends AbstractOneShareTest {
    @Inject
    private ApplicationContext applicationContext;

    @Inject
    private FlowListeners flowListenersService;

    @Inject
    @Named(QueueFactoryInterface.EXECUTION_NAMED)
    private QueueInterface<Execution> executionQueue;

    @Inject
    protected LocalFlowRepositoryLoader repositoryLoader;

    @Inject
    protected OnesShareTestUtils testUtils;

    @Value("${kestra.tasks.oneshare.tenantId}")
    private String tenantId;
    @Value("${kestra.tasks.oneshare.clientId}")
    private String clientId;
    @Value("${kestra.tasks.oneshare.clientSecret}")
    private String clientSecret;
    @Value("${kestra.tasks.oneshare.driveId}")
    private String driveId;

    @Test
    void listenFromFlow() throws Exception {

        // mock flow listeners
        CountDownLatch queueCount = new CountDownLatch(1);

        try (
            DefaultWorker worker = applicationContext.createBean(DefaultWorker.class, IdUtils.create(), 8, null);
            AbstractScheduler scheduler = new JdbcScheduler(
                this.applicationContext,
                this.flowListenersService
            )
        ) {
            AtomicReference<Execution> last = new AtomicReference<>();

            // wait for execution
            Flux<Execution> receive = TestsUtils.receive(executionQueue, executionWithError -> {
                Execution execution = executionWithError.getLeft();
                if (execution.getFlowId().equals("oneshare-listen")) {
                    last.set(execution);
                    queueCount.countDown();
                }
            });

            // prepare two files in the monitored folder
            String out1 = FriendlyId.createFriendlyId() + ".yml";
            testUtils.uploadNamed("Documents/TestTrigger", out1);
            String out2 = FriendlyId.createFriendlyId() + ".yml";
            testUtils.uploadNamed("Documents/TestTrigger", out2);

            worker.run();
            scheduler.run();
            repositoryLoader.load(MAIN_TENANT, Objects.requireNonNull(TriggerTest.class.getClassLoader().getResource("flows/oneshare")));

            boolean await = queueCount.await(60, TimeUnit.SECONDS);
            try {
                assertThat(await, is(true));
            } finally {
                receive.blockLast();
            }

            @SuppressWarnings("unchecked")
            List<Object> files = (List<Object>) last.get().getTrigger().getVariables().get("files");
            assertThat(files.size(), greaterThanOrEqualTo(2));
        }
    }

    @Test
    void shouldDetectNewFile() throws Exception {

        Trigger trigger = Trigger.builder()
            .id(TriggerTest.class.getSimpleName() + IdUtils.create())
            .type(Trigger.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .path(Property.ofValue("/Documents/TestTrigger"))
            .interval(Duration.ofSeconds(10))
            .build();

        String fileName = FriendlyId.createFriendlyId() + ".txt";
        testUtils.uploadNamed("Documents/TestTrigger", fileName);

        // Give delta API time to process
        Thread.sleep(2000);

        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(true));

        @SuppressWarnings("unchecked")
        List<OneShareFile> files = (List<OneShareFile>) execution.get().getTrigger().getVariables().get("files");
        assertThat(files.size(), greaterThanOrEqualTo(1));

        // Verify file properties
        boolean hasExpectedFile = files.stream().anyMatch(f -> f.getName().equals(fileName));
        assertThat(hasExpectedFile, is(true));
    }

    @Test
    void shouldNotTriggerWithoutNewFiles() throws Exception {

        Trigger trigger = Trigger.builder()
            .id(TriggerTest.class.getSimpleName() + IdUtils.create())
            .type(Trigger.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .path(Property.ofValue("/Documents/EmptyTestFolder"))
            .interval(Duration.ofSeconds(10))
            .build();

        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        // First evaluation should return empty as no new files
        assertThat(execution.isPresent(), is(false));
    }

    @Test
    void shouldDetectMultipleFiles() throws Exception {

        Trigger trigger = Trigger.builder()
            .id(TriggerTest.class.getSimpleName() + IdUtils.create())
            .type(Trigger.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .path(Property.ofValue("/Documents/TestTriggerMultiple"))
            .interval(Duration.ofSeconds(10))
            .build();

        // Upload multiple files
        String file1 = FriendlyId.createFriendlyId() + ".txt";
        String file2 = FriendlyId.createFriendlyId() + ".txt";
        String file3 = FriendlyId.createFriendlyId() + ".txt";

        testUtils.uploadNamed("Documents/TestTriggerMultiple", file1);
        testUtils.uploadNamed("Documents/TestTriggerMultiple", file2);
        testUtils.uploadNamed("Documents/TestTriggerMultiple", file3);

        // Give delta API time to process
        Thread.sleep(2000);

        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(true));

        @SuppressWarnings("unchecked")
        List<OneShareFile> files = (List<OneShareFile>) execution.get().getTrigger().getVariables().get("files");
        assertThat(files.size(), greaterThanOrEqualTo(3));
    }

    @Test
    void shouldPersistStateAcrossEvaluations() throws Exception {

        String triggerId = TriggerTest.class.getSimpleName() + IdUtils.create();
        Trigger trigger = Trigger.builder()
            .id(triggerId)
            .type(Trigger.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .path(Property.ofValue("/Documents/TestTriggerState"))
            .interval(Duration.ofSeconds(10))
            .build();

        // First upload and evaluation
        String file1 = FriendlyId.createFriendlyId() + ".txt";
        testUtils.uploadNamed("Documents/TestTriggerState", file1);
        Thread.sleep(2000);

        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context1 = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution1 = trigger.evaluate(context1.getKey(), context1.getValue());

        assertThat(execution1.isPresent(), is(true));

        @SuppressWarnings("unchecked")
        List<OneShareFile> files1 = (List<OneShareFile>) execution1.get().getTrigger().getVariables().get("files");
        assertThat(files1.size(), greaterThanOrEqualTo(1));

        // Second evaluation without new files - should not trigger
        Thread.sleep(2000);
        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context2 = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution2 = trigger.evaluate(context2.getKey(), context2.getValue());

        assertThat(execution2.isPresent(), is(false));

        // Upload a new file and evaluate again - should trigger
        String file2 = FriendlyId.createFriendlyId() + ".txt";
        testUtils.uploadNamed("Documents/TestTriggerState", file2);
        Thread.sleep(2000);

        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context3 = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution3 = trigger.evaluate(context3.getKey(), context3.getValue());

        assertThat(execution3.isPresent(), is(true));

        @SuppressWarnings("unchecked")
        List<OneShareFile> files3 = (List<OneShareFile>) execution3.get().getTrigger().getVariables().get("files");

        // Should detect at least the new file
        assertThat(files3.size(), greaterThanOrEqualTo(1));
        
        // Verify the new file (file2) is present
        boolean hasNewFile = files3.stream().anyMatch(f -> f.getName().equals(file2));
        assertThat("New file should be detected", hasNewFile, is(true));
        
        // Verify the old file (file1) is NOT present (it was already seen in execution1)
        boolean hasOldFile = files3.stream().anyMatch(f -> f.getName().equals(file1));
        assertThat("Old file should not be detected again", hasOldFile, is(false));
    }

    @Test
    void shouldHandleDriveIdAndSiteId() throws Exception {

        // Test with driveId
        Trigger triggerWithDrive = Trigger.builder()
            .id(TriggerTest.class.getSimpleName() + IdUtils.create())
            .type(Trigger.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .path(Property.ofValue("/Documents"))
            .interval(Duration.ofSeconds(10))
            .build();

        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = TestsUtils.mockTrigger(runContextFactory, triggerWithDrive);

        // Should not throw exception
        Optional<Execution> execution = triggerWithDrive.evaluate(context.getKey(), context.getValue());

        // Execution may or may not be present depending on files, but should not throw
        assertThat(execution, notNullValue());
    }
}
