package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.models.security.HuntingQueryResults;
import com.microsoft.graph.models.security.SinglePropertySchema;
import com.microsoft.graph.security.microsoftgraphsecurityrunhuntingquery.RunHuntingQueryPostRequestBody;
import com.microsoft.kiota.ApiException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run a Microsoft Defender XDR advanced hunting query",
    description = "Runs a KQL query with Microsoft Graph `runHuntingQuery` and returns the rows with typed values (dates, numbers, booleans) according to the query schema. " +
        "Requires the Microsoft Graph application permission ThreatHunting.Read.All."
)
@Plugin(
    examples = {
        @Example(
            title = "Store a day of PowerShell process events for a device",
            full = true,
            code = """
                id: defender_hunting
                namespace: company.security

                inputs:
                  - id: deviceName
                    type: STRING

                tasks:
                  - id: hunt
                    type: io.kestra.plugin.microsoft365.defender.HuntingQuery
                    tenantId: "{{ secret('AZURE_TENANT_ID') }}"
                    clientId: "{{ secret('AZURE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AZURE_CLIENT_SECRET') }}"
                    timespan: P1D
                    query: |
                      DeviceProcessEvents
                      | where DeviceName == "{{ inputs.deviceName }}"
                      | where FileName =~ "powershell.exe"
                      | project Timestamp, AccountName, ProcessCommandLine

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.hunt.count }} rows stored in {{ outputs.hunt.uri }}"
                """
        )
    }
)
public class HuntingQuery extends AbstractDefender implements RunnableTask<HuntingQuery.Output> {

    @Schema(title = "KQL query", description = "Advanced hunting query in Kusto Query Language (KQL).")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> query;

    @Schema(
        title = "Time span",
        description = "How far back the query looks, as an ISO-8601 duration. Microsoft Graph defaults to 30 days when no time span is sent; this task always sends one."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Duration> timespan = Property.ofValue(Duration.ofDays(7));

    @Schema(
        title = "How to output the rows",
        description = """
            FETCH - outputs the rows as `rows`
            FETCH_ONE - outputs the first row as `row`
            STORE - stores the rows in an ION file and outputs its `uri`
            NONE - only outputs `count` and `schema`"""
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rQuery = runContext.render(query).as(String.class).orElseThrow();
        Duration rTimespan = runContext.render(timespan).as(Duration.class).orElse(Duration.ofDays(7));
        FetchType rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.STORE);

        RunHuntingQueryPostRequestBody body = new RunHuntingQueryPostRequestBody();
        body.setQuery(rQuery);
        body.setTimespan(isoTimespan(rTimespan));

        runContext.logger().info("Running Defender advanced hunting query over {}", body.getTimespan());

        HuntingQueryResults results;
        try {
            results = graphClient(runContext).security().microsoftGraphSecurityRunHuntingQuery().post(body);
        } catch (ApiException e) {
            throw DefenderUtils.graphError(e, "run the advanced hunting query");
        }

        List<Map<String, String>> schema = new ArrayList<>();
        Map<String, String> typesByColumn = new HashMap<>();
        if (results != null && results.getSchema() != null) {
            for (SinglePropertySchema column : results.getSchema()) {
                schema.add(Map.of("name", column.getName(), "type", column.getType() == null ? "" : column.getType()));
                // Result keys can differ in case from the schema names, so look types up case-insensitively
                typesByColumn.put(column.getName().toLowerCase(Locale.ROOT), column.getType());
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        if (results != null && results.getResults() != null) {
            for (Map<String, Object> raw : DefenderUtils.toMaps(results.getResults())) {
                Map<String, Object> row = new LinkedHashMap<>();
                raw.forEach((key, value) -> row.put(key, convert(value, typesByColumn.get(key.toLowerCase(Locale.ROOT)))));
                rows.add(row);
            }
        }

        runContext.logger().info("Advanced hunting query returned {} rows", rows.size());

        Fetched fetched = fetch(runContext, rFetchType, rows);
        return Output.builder()
            .schema(schema)
            .rows(fetched.items())
            .row(fetched.first())
            .uri(fetched.uri())
            .count(rows.size())
            .build();
    }

    /**
     * Whole days are sent as {@code P<n>D}, which is what the Defender portal uses, instead of {@code Duration}'s {@code PT<n>H}.
     */
    static String isoTimespan(Duration duration) {
        if (!duration.isZero() && duration.toSeconds() % Duration.ofDays(1).toSeconds() == 0 && duration.getNano() == 0) {
            return "P" + duration.toDays() + "D";
        }
        return duration.toString();
    }

    /**
     * Converts a JSON value to the Java type matching its Kusto column type.
     */
    static Object convert(Object value, String type) {
        if (value == null || type == null) {
            return value;
        }
        try {
            return switch (type.toLowerCase(Locale.ROOT)) {
                case "datetime", "date" -> value instanceof String s ? parseInstant(s) : value;
                case "int64", "long" -> value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
                case "int32", "int" -> value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
                case "double", "real", "decimal" -> value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
                case "boolean", "bool" -> value instanceof Boolean ? value : Boolean.parseBoolean(value.toString());
                default -> value;
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            return value;
        }
    }

    private static Instant parseInstant(String value) {
        return OffsetDateTime.parse(value).toInstant();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Query schema", description = "Column names and Kusto types, as `name` and `type`.")
        private final List<Map<String, String>> schema;

        @Schema(title = "Rows (FETCH)")
        private final List<Map<String, Object>> rows;

        @Schema(title = "First row (FETCH_ONE)")
        private final Map<String, Object> row;

        @Schema(title = "URI of the ION file with the rows (STORE)")
        private final URI uri;

        @Schema(title = "Number of rows returned")
        private final int count;
    }
}
