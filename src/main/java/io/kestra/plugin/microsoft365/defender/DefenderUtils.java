package io.kestra.plugin.microsoft365.defender;

import com.fasterxml.jackson.core.type.TypeReference;
import com.microsoft.graph.models.odataerrors.ODataError;
import com.microsoft.kiota.ApiException;
import com.microsoft.kiota.serialization.KiotaJsonSerialization;
import com.microsoft.kiota.serialization.Parsable;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import reactor.core.publisher.Flux;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// Shared by the Defender tasks and the Defender trigger, which cannot share a base class
final class DefenderUtils {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private DefenderUtils() {
    }

    /** Converts a Graph SDK model into a map with the Graph JSON field names, for Pebble expressions and ION files. */
    static Map<String, Object> toMap(Parsable parsable) throws IOException {
        if (parsable == null) {
            return null;
        }
        // serializeOnlyChangedValues = false: deserialized models have no "changed" values in their backing store
        var json = KiotaJsonSerialization.serializeAsString(parsable, false);
        var map = JacksonMapper.ofJson().readValue(json, MAP_TYPE);
        map.remove("@odata.type");
        return map;
    }

    static List<Map<String, Object>> toMaps(Collection<? extends Parsable> parsables) throws IOException {
        var maps = new ArrayList<Map<String, Object>>();
        if (parsables != null) {
            for (var parsable : parsables) {
                maps.add(toMap(parsable));
            }
        }
        return maps;
    }

    static String combineFilters(String... clauses) {
        var parts = Arrays.stream(clauses)
            .filter(c -> c != null && !c.isBlank())
            .toList();
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        return parts.stream().map("(%s)"::formatted).collect(Collectors.joining(" and "));
    }

    static String eqClause(String field, GraphEnum value) {
        return value == null ? null : "%s eq '%s'".formatted(field, value.toGraph().getValue());
    }

    static <P, T> List<T> collectPages(P firstPage,
                                       Function<P, List<T>> values,
                                       Function<P, String> nextLink,
                                       Function<String, P> fetchNext,
                                       int max) {
        var items = new ArrayList<T>();
        var page = firstPage;
        while (page != null) {
            var pageValues = values.apply(page);
            if (pageValues != null) {
                for (var value : pageValues) {
                    if (items.size() >= max) {
                        return items;
                    }
                    items.add(value);
                }
            }
            var next = nextLink.apply(page);
            if (items.size() >= max || next == null) {
                break;
            }
            page = fetchNext.apply(next);
        }
        return items;
    }

    static URI store(RunContext runContext, List<?> rows) throws IOException {
        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        try (var writer = new BufferedWriter(new FileWriter(tempFile), FileSerde.BUFFER_SIZE)) {
            FileSerde.writeAll(writer, Flux.fromIterable(rows)).block();
        }
        return runContext.storage().putFile(tempFile);
    }

    static RuntimeException graphError(ApiException e, String action) {
        var status = e.getResponseStatusCode();
        var detail = errorMessage(e);
        var message = switch (status) {
            case 429 -> "Microsoft Graph throttled the request while trying to %s (HTTP 429%s). Add a `retry` policy to the task to retry automatically."
                .formatted(action, retryAfter(e).map(", Retry-After: %ss"::formatted).orElse(""));
            case 401 -> "Authentication failed while trying to %s (HTTP 401). Check tenantId, clientId and the client secret or certificate."
                .formatted(action);
            case 403 -> "Permission denied while trying to %s (HTTP 403). Check that the app registration was granted the required application permission (SecurityAlert.*, SecurityIncident.* or ThreatHunting.Read.All) with admin consent."
                .formatted(action);
            case 404 -> "Not found while trying to %s (HTTP 404).".formatted(action);
            default -> "Microsoft Graph error while trying to %s (HTTP %d).".formatted(action, status);
        };
        if (detail != null) {
            message = "%s %s".formatted(message, detail);
        }
        return new IllegalStateException(message, e);
    }

    static boolean isTransient(ApiException e) {
        var status = e.getResponseStatusCode();
        return status == 429 || status == 503 || status == 504;
    }

    private static String errorMessage(ApiException e) {
        if (e instanceof ODataError oDataError && oDataError.getError() != null) {
            var code = oDataError.getError().getCode();
            var msg = oDataError.getError().getMessage();
            return code != null ? "%s: %s".formatted(code, msg) : msg;
        }
        return e.getMessage();
    }

    private static Optional<String> retryAfter(ApiException e) {
        if (e.getResponseHeaders() == null) {
            return Optional.empty();
        }
        return e.getResponseHeaders().entrySet().stream()
            .filter(h -> "retry-after".equalsIgnoreCase(h.getKey()))
            .map(Map.Entry::getValue)
            .filter(v -> v != null && !v.isEmpty())
            .map(DefenderUtils::first)
            .findFirst();
    }

    private static String first(Set<String> values) {
        return values.iterator().next();
    }
}
