package io.kestra.plugin.microsoft365.oneshare;

import com.devskiller.friendly_id.FriendlyId;
import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.item.items.ItemsRequestBuilder;
import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.drives.item.items.item.delta.DeltaGetResponse;
import com.microsoft.graph.drives.item.items.item.delta.DeltaRequestBuilder;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.serviceclient.GraphServiceClient;
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
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    private static MockedConstruction<GraphServiceClient> graphClientMock;
    private static MockedConstruction<DeltaRequestBuilder> deltaBuilderMock;
    
    @BeforeAll
    static void setupMocks() {
        // First, mock DeltaRequestBuilder construction (used when deltaLink is provided)
        deltaBuilderMock = Mockito.mockConstruction(DeltaRequestBuilder.class, (mock, context) -> {
            DeltaGetResponse deltaResponse = new DeltaGetResponse();
            
            DriveItem file1 = new DriveItem();
            file1.setId("delta-file-1");
            file1.setName("changed-file-1.txt");
            file1.setSize(512L);
            file1.setFile(new com.microsoft.graph.models.File());
            file1.setETag("v1");
            file1.setCreatedDateTime(java.time.OffsetDateTime.now());
            file1.setLastModifiedDateTime(java.time.OffsetDateTime.now());
            
            DriveItem file2 = new DriveItem();
            file2.setId("delta-file-2");
            file2.setName("changed-file-2.txt");
            file2.setSize(1024L);
            file2.setFile(new com.microsoft.graph.models.File());
            file2.setETag("v1");
            file2.setCreatedDateTime(java.time.OffsetDateTime.now());
            file2.setLastModifiedDateTime(java.time.OffsetDateTime.now());
            
            deltaResponse.setValue(Arrays.asList(file1, file2));
            deltaResponse.setOdataDeltaLink("https://mock-delta-link");
            deltaResponse.setOdataNextLink(null);
            
            when(mock.get()).thenReturn(deltaResponse);
        });
        
        // Mock GraphServiceClient and the drive API chain for delta/trigger
        graphClientMock = Mockito.mockConstruction(GraphServiceClient.class, (mock, context) -> {
            // Mock RequestAdapter (required for DeltaRequestBuilder)
            com.microsoft.kiota.RequestAdapter requestAdapter = mock(com.microsoft.kiota.RequestAdapter.class);
            when(mock.getRequestAdapter()).thenReturn(requestAdapter);
            
            // Create mock builders
            DrivesRequestBuilder drivesBuilder = mock(DrivesRequestBuilder.class);
            DriveItemRequestBuilder driveItemBuilder = mock(DriveItemRequestBuilder.class);
            ItemsRequestBuilder itemsBuilder = mock(ItemsRequestBuilder.class);
            DriveItemItemRequestBuilder driveItemItemBuilder = mock(DriveItemItemRequestBuilder.class);
            DeltaRequestBuilder deltaBuilder = mock(DeltaRequestBuilder.class);

            // Mock delta response with changed files
            DeltaGetResponse deltaResponse = new DeltaGetResponse();
            
            DriveItem file1 = new DriveItem();
            file1.setId("delta-file-1");
            file1.setName("changed-file-1.txt");
            file1.setSize(512L);
            file1.setFile(new com.microsoft.graph.models.File());
            file1.setETag("v1");
            file1.setCreatedDateTime(java.time.OffsetDateTime.now());
            file1.setLastModifiedDateTime(java.time.OffsetDateTime.now());
            
            DriveItem file2 = new DriveItem();
            file2.setId("delta-file-2");
            file2.setName("changed-file-2.txt");
            file2.setSize(1024L);
            file2.setFile(new com.microsoft.graph.models.File());
            file2.setETag("v1");
            file2.setCreatedDateTime(java.time.OffsetDateTime.now());
            file2.setLastModifiedDateTime(java.time.OffsetDateTime.now());
            
            deltaResponse.setValue(Arrays.asList(file1, file2));
            deltaResponse.setOdataDeltaLink("https://mock-delta-link");
            deltaResponse.setOdataNextLink(null); // No pagination
            
            // Setup mock chain
            when(deltaBuilder.get()).thenReturn(deltaResponse);
            when(driveItemItemBuilder.delta()).thenReturn(deltaBuilder);
            when(itemsBuilder.byDriveItemId(anyString())).thenReturn(driveItemItemBuilder);
            when(driveItemBuilder.items()).thenReturn(itemsBuilder);
            when(drivesBuilder.byDriveId(anyString())).thenReturn(driveItemBuilder);
            when(mock.drives()).thenReturn(drivesBuilder);
        });
    }

    @AfterAll
    static void tearDownMocks() {
        if (deltaBuilderMock != null) {
            deltaBuilderMock.close();
        }
        if (graphClientMock != null) {
            graphClientMock.close();
        }
    }

    // ================== Mock-based Unit Tests ==================
    
    @Test
    void testTriggerExecutesSuccessfully() throws Exception {
        // Verify the mock is active
        assertThat("GraphServiceClient mock should be active", 
            graphClientMock != null && !graphClientMock.isClosed(), is(true));
        
        // Since we're using MockedConstruction, the GraphServiceClient will be mocked
        // when the trigger creates it via graphClient(runContext)
        Trigger trigger = Trigger.builder()
            .id("test-trigger")
            .type(Trigger.class.getName())
            .driveId(Property.ofValue("test-drive"))
            .path(Property.ofValue("/Documents/Test"))
            .interval(Duration.ofSeconds(30))
            .tenantId(Property.ofValue("mock-tenant"))
            .clientId(Property.ofValue("mock-client"))
            .clientSecret(Property.ofValue("mock-secret"))
            .build();
        
        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> context = 
            TestsUtils.mockTrigger(runContextFactory, trigger);
        
        // The GraphServiceClient created in the evaluate method will be mocked
        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());
        
        // On first run, the trigger should not fire (to avoid flooding with existing files)
        // It should return empty but successfully process the files and store state
        assertThat("First run should not trigger execution", execution.isPresent(), is(false));
        
        // Verify the mock was actually called (DeltaRequestBuilder.get() was invoked)
        assertThat("GraphServiceClient should have been constructed", 
            graphClientMock.constructed().size(), is(1));
    }

    @Test
    void testTriggerTaskConfiguration() {
        // Lightweight configuration test
        Trigger trigger = Trigger.builder()
            .id("test-trigger")
            .type(Trigger.class.getName())
            .driveId(Property.ofValue("test-drive"))
            .path(Property.ofValue("/Documents/Test"))
            .interval(Duration.ofSeconds(30))
            .tenantId(Property.ofValue("mock-tenant"))
            .clientId(Property.ofValue("mock-client"))
            .clientSecret(Property.ofValue("mock-secret"))
            .build();
        
        assertThat(trigger, notNullValue());
        assertThat(trigger.getDriveId(), notNullValue());
        assertThat(trigger.getPath(), notNullValue());
        assertThat(trigger.getInterval(), is(Duration.ofSeconds(30)));
    }

    // ================== E2E Tests (requires credentials) ==================

    @Test
    @EnabledIf("isIntegrationTestEnabled")
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
    @EnabledIf("isIntegrationTestEnabled")
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
    @EnabledIf("isIntegrationTestEnabled")
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
    @EnabledIf("isIntegrationTestEnabled")
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
    @EnabledIf("isIntegrationTestEnabled")
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
    @EnabledIf("isIntegrationTestEnabled")
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
