package io.kestra.plugin.microsoft365.teams;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.repositories.LocalFlowRepositoryLoader;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.runners.TestRunner;
import io.kestra.core.serializers.JacksonMapper;
import jakarta.inject.Inject;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@KestraTest
class TeamsExecutionTest extends AbstractTeamsTest {
    @Inject
    protected TestRunner runner;

    @Inject
    protected LocalFlowRepositoryLoader repositoryLoader;

    @Inject
    protected RunContextFactory runContextFactory;

    @BeforeEach
    protected void init() throws IOException, URISyntaxException {
        repositoryLoader.load(Objects.requireNonNull(TeamsExecutionTest.class.getClassLoader().getResource("flows/common")));
        repositoryLoader.load(Objects.requireNonNull(TeamsExecutionTest.class.getClassLoader().getResource("flows/teams")));
        this.runner.run();
    }

    @Test
    void flow() throws Exception {
        var failedExecution = runAndCaptureExecution(
            "main-flow-that-fails",
            "teams"
        );

        String receivedData = waitForWebhookData(() -> FakeWebhookController.data,5000);

        assertThat(receivedData, containsString(failedExecution.getId()));
        assertThat(receivedData, containsString("https://mysuperhost.com/kestra/ui"));
        assertThat(receivedData, containsString("Failed on task `failed`"));
        assertThat(receivedData, containsString("{\"name\":\"Final task ID\",\"value\":\"failed\"}"));
        assertThat(receivedData, containsString("Kestra Teams notification"));
        assertThat(receivedData, containsString("{\"name\":\"Message\",\"value\":\"Test test test\"}"));
        assertThat(receivedData, containsString("{\"name\":\"severity\",\"value\":\"high\"}"));
        assertThat(receivedData, containsString("{\"name\":\"region\",\"value\":\"us-east\"}"));
        assertThat(receivedData, not(containsString(",]")));
    }

    @Test
    void flowWithoutCustomFields() throws Exception {
        var succeededExecution = runAndCaptureExecution(
            "main-flow-that-succeeds",
            "teams-default"
        );

        String receivedData = waitForWebhookData(() -> FakeWebhookController.data, 5000);

        assertThat(receivedData, containsString(succeededExecution.getId()));
        assertThat(receivedData, containsString("Succeeded"));
        assertThat(receivedData, containsString("{\"name\":\"Final task ID\",\"value\":\"success\"}"));
        assertThat(receivedData, not(containsString("\"name\":\"Message\"")));
        assertThat(receivedData, not(containsString("\"name\":\"severity\"")));
        assertThat(receivedData, not(containsString(",]")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void flowWithHostileContent() throws Exception {
        var failedExecution = runAndCaptureExecution(
            "main-flow-that-fails-hostile",
            "teams-hostile"
        );

        String receivedData = waitForWebhookData(() -> FakeWebhookController.data, 5000);

        // deserializing (not containsString) is what actually proves the payload is valid JSON despite the quotes/backslash/newline in user input
        Map<String, Object> payload = (Map<String, Object>) JacksonMapper.ofJson().readValue(receivedData, Object.class);

        var sections = (List<Map<String, Object>>) payload.get("sections");
        var section = sections.get(0);
        assertThat(section.get("activityTitle"), is("Alert for \"prod\" pipeline \\ escalation"));
        assertThat(section.get("activitySubtitle"), is("Line one\nLine two with \"quotes\""));

        var facts = (List<Map<String, Object>>) section.get("facts");
        assertThat(facts, hasSize(8));
        assertThat(facts.get(0).get("value"), is("io.kestra.tests"));
        assertThat(facts.get(1).get("value"), is("main-flow-that-fails-hostile"));
        assertThat(facts.get(2).get("value"), is(failedExecution.getId()));
        assertThat(facts.get(3).get("value"), is("FAILED"));
        assertThat(facts.get(4).get("value"), is("failed"));

        String summary = (String) payload.get("summary");
        assertThat(summary, containsString("[io.kestra.tests] main-flow-that-fails-hostile"));
        assertThat(summary, containsString("\n> Failed on task `failed` after"));

        var retriesFact = facts.stream().filter(f -> "retries".equals(f.get("name"))).findFirst()
            .orElseThrow(() -> new AssertionError("no 'retries' fact in payload: " + payload));
        assertThat(retriesFact.get("value"), is("3"));

        var urgentFact = facts.stream().filter(f -> "urgent".equals(f.get("name"))).findFirst()
            .orElseThrow(() -> new AssertionError("no 'urgent' fact in payload: " + payload));
        assertThat(urgentFact.get("value"), is("true"));

        // structured values are sent as their JSON text, not Java toString()
        var detailsFact = facts.stream().filter(f -> "details".equals(f.get("name"))).findFirst()
            .orElseThrow(() -> new AssertionError("no 'details' fact in payload: " + payload));
        assertThat(detailsFact.get("value"), is("{\"host\":\"db1\",\"codes\":[500,502]}"));
    }

    // core 1.3.x omits firstFailed and lastTask when no task run matches the state filter (e.g. a WARNING or KILLED execution)
    @Test
    void templateRendersWhenFirstFailedAndLastTaskAreAbsent() throws Exception {
        assertThat(renderFinalTaskId(baseRenderMap()), is(""));
    }

    // newer cores always set firstFailed and set lastTask to null when there is no task run
    @Test
    void templateRendersWhenLastTaskIsNull() throws Exception {
        var renderMap = baseRenderMap();
        renderMap.put("firstFailed", false);
        renderMap.put("lastTask", null);
        assertThat(renderFinalTaskId(renderMap), is(""));
    }

    private static HashMap<String, Object> baseRenderMap() {
        var renderMap = new HashMap<String, Object>();
        renderMap.put("themeColor", "0076D7");
        renderMap.put("link", "https://mysuperhost.com/kestra/ui");
        renderMap.put("duration", "00:00:01.000");
        renderMap.put("execution", Map.of(
            "namespace", "io.kestra.tests",
            "flowId", "no-task-yet",
            "id", "no-task-yet-id",
            "state", Map.of("current", "WARNING")
        ));
        return renderMap;
    }

    @SuppressWarnings("unchecked")
    private Object renderFinalTaskId(Map<String, Object> renderMap) throws Exception {
        var template = IOUtils.toString(
            Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("teams-template.peb")),
            StandardCharsets.UTF_8
        );
        var rendered = runContextFactory.of(Map.of()).render(template, renderMap);
        Map<String, Object> payload = (Map<String, Object>) JacksonMapper.ofJson().readValue(rendered, Object.class);

        assertThat((String) payload.get("summary"), containsString("\n> Succeeded after"));

        var section = ((List<Map<String, Object>>) payload.get("sections")).get(0);
        var facts = (List<Map<String, Object>>) section.get("facts");
        return facts.stream().filter(f -> "Final task ID".equals(f.get("name"))).findFirst()
            .orElseThrow(() -> new AssertionError("no 'Final task ID' fact in payload: " + payload))
            .get("value");
    }
}
