package io.kestra.plugin.microsoft365.defender;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class HuntingQueryTest extends AbstractDefenderTest {
    // Example 2 of the Graph docs: result keys are camelCased while the schema uses the KQL column names
    private static final String RESPONSE = """
        {
          "schema": [
            {"name": "Timestamp", "type": "DateTime"},
            {"name": "FileName", "type": "String"},
            {"name": "Count", "type": "Int64"},
            {"name": "Score", "type": "Double"},
            {"name": "IsSigned", "type": "Boolean"},
            {"name": "Details", "type": "Object"}
          ],
          "results": [
            {"timestamp": "2026-10-24T09:40:12.1234567Z", "fileName": "powershell.exe", "count": 3, "score": 0.5, "isSigned": true,
             "details": {"parent": "cmd.exe", "args": ["-enc", "AAA"]}},
            {"timestamp": "2026-10-24T09:41:00Z", "fileName": "cmd.exe", "count": 12345678901, "score": 1, "isSigned": false, "details": null}
          ]
        }""";

    @Test
    void fetchConvertsValuesFromSchema() throws Exception {
        wm.stubFor(post(urlPathEqualTo(GRAPH + "/security/microsoft.graph.security.runHuntingQuery")).willReturn(okJson(RESPONSE)));

        HuntingQuery.Output output = withWireMock(HuntingQuery.builder()
            .query(Property.ofValue("DeviceProcessEvents | take 2"))
            .timespan(Property.ofValue(Duration.ofDays(1)))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build()).run(runContext());

        wm.verify(postRequestedFor(urlPathEqualTo(GRAPH + "/security/microsoft.graph.security.runHuntingQuery"))
            .withRequestBody(matchingJsonPath("$.query", equalTo("DeviceProcessEvents | take 2")))
            .withRequestBody(matchingJsonPath("$.timespan", equalTo("P1D"))));

        assertThat(output.getCount(), is(2));
        assertThat(output.getSchema(), hasItem(Map.of("name", "Timestamp", "type", "DateTime")));

        Map<String, Object> row = output.getRows().getFirst();
        assertThat(row.get("timestamp"), is(Instant.parse("2026-10-24T09:40:12.1234567Z")));
        assertThat(row.get("fileName"), is("powershell.exe"));
        assertThat(row.get("count"), is(3L));
        assertThat(row.get("score"), is(0.5));
        assertThat(row.get("isSigned"), is(true));
        assertThat(((Map<?, ?>) row.get("details")).get("parent"), is("cmd.exe"));

        Map<String, Object> second = output.getRows().get(1);
        assertThat(second.get("count"), is(12345678901L));
        assertThat(second.get("score"), is(1.0));
        assertThat(second.get("details"), nullValue());
    }

    @Test
    void defaultsToSevenDaysAndStore() throws Exception {
        wm.stubFor(post(urlPathEqualTo(GRAPH + "/security/microsoft.graph.security.runHuntingQuery")).willReturn(okJson(RESPONSE)));

        HuntingQuery.Output output = withWireMock(HuntingQuery.builder()
            .query(Property.ofValue("DeviceProcessEvents | take 2"))
            .build()).run(runContext());

        wm.verify(postRequestedFor(urlPathEqualTo(GRAPH + "/security/microsoft.graph.security.runHuntingQuery"))
            .withRequestBody(matchingJsonPath("$.timespan", equalTo("P7D"))));
        assertThat(output.getUri(), notNullValue());
        assertThat(output.getRows(), nullValue());
        assertThat(output.getCount(), is(2));
    }

    @Test
    void isoTimespan() {
        assertThat(HuntingQuery.isoTimespan(Duration.ofDays(7)), is("P7D"));
        assertThat(HuntingQuery.isoTimespan(Duration.ofHours(36)), is("PT36H"));
        assertThat(HuntingQuery.isoTimespan(Duration.ofMinutes(90)), is("PT1H30M"));
    }
}
