package io.kestra.plugin.microsoft365.defender;

import com.fasterxml.jackson.core.type.TypeReference;
import com.microsoft.graph.models.odataerrors.ODataError;
import com.microsoft.kiota.ApiException;
import com.microsoft.kiota.serialization.KiotaJsonSerialization;
import com.microsoft.kiota.serialization.Parsable;
import com.microsoft.kiota.serialization.ValuedEnum;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import reactor.core.publisher.Flux;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Helpers shared by the Defender tasks and the Defender trigger (which cannot share a base class).
 */
final class DefenderUtils {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String UNKNOWN_FUTURE_VALUE = "unknownFutureValue";

    private DefenderUtils() {
    }

    /**
     * Converts a Graph SDK model into a plain map with the same field names as the Graph JSON payload,
     * so outputs can be used from Pebble expressions and stored as ION.
     */
    static Map<String, Object> toMap(Parsable parsable) throws IOException {
        if (parsable == null) {
            return null;
        }
        // serializeOnlyChangedValues = false: deserialized models have no "changed" values in their backing store
        String json = KiotaJsonSerialization.serializeAsString(parsable, false);
        Map<String, Object> map = JacksonMapper.ofJson().readValue(json, MAP_TYPE);
        map.remove("@odata.type");
        return map;
    }

    static List<Map<String, Object>> toMaps(Collection<? extends Parsable> parsables) throws IOException {
        List<Map<String, Object>> maps = new ArrayList<>();
        if (parsables != null) {
            for (Parsable parsable : parsables) {
                maps.add(toMap(parsable));
            }
        }
        return maps;
    }

    /**
     * Resolves a Graph enum from its wire value (e.g. {@code inProgress}), failing with the list of allowed values.
     */
    static <E extends Enum<E> & ValuedEnum> E parseEnum(Class<E> type, String value, String property) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        for (E constant : type.getEnumConstants()) {
            if (constant.getValue().equalsIgnoreCase(trimmed) && !UNKNOWN_FUTURE_VALUE.equals(constant.getValue())) {
                return constant;
            }
        }
        String allowed = Arrays.stream(type.getEnumConstants())
            .map(ValuedEnum::getValue)
            .filter(v -> !UNKNOWN_FUTURE_VALUE.equals(v))
            .collect(Collectors.joining(", "));
        throw new IllegalArgumentException("Invalid " + property + " '" + value + "'. Allowed values: " + allowed);
    }

    /**
     * Joins the user's raw OData filter with the convenience filters, all combined with {@code and}.
     */
    static String combineFilters(String... clauses) {
        List<String> parts = Arrays.stream(clauses)
            .filter(c -> c != null && !c.isBlank())
            .toList();
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        return parts.stream().map(p -> "(" + p + ")").collect(Collectors.joining(" and "));
    }

    static String eqClause(String field, ValuedEnum value) {
        return value == null ? null : field + " eq '" + value.getValue() + "'";
    }

    /**
     * Reads pages until {@code max} items are collected or there is no more {@code @odata.nextLink};
     * never keeps more than {@code max} items in memory.
     */
    static <P, T> List<T> collectPages(P firstPage,
                                       Function<P, List<T>> values,
                                       Function<P, String> nextLink,
                                       Function<String, P> fetchNext,
                                       int max) {
        List<T> items = new ArrayList<>();
        P page = firstPage;
        while (page != null) {
            List<T> pageValues = values.apply(page);
            if (pageValues != null) {
                for (T value : pageValues) {
                    if (items.size() >= max) {
                        return items;
                    }
                    items.add(value);
                }
            }
            String next = nextLink.apply(page);
            if (items.size() >= max || next == null) {
                break;
            }
            page = fetchNext.apply(next);
        }
        return items;
    }

    static URI store(RunContext runContext, List<?> rows) throws IOException {
        File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile), FileSerde.BUFFER_SIZE)) {
            FileSerde.writeAll(writer, Flux.fromIterable(rows)).block();
        }
        return runContext.storage().putFile(tempFile);
    }

    /**
     * Turns a Graph error into an actionable exception; throttling errors carry the Retry-After value
     * so that a Kestra {@code retry} policy can be tuned from it.
     */
    static RuntimeException graphError(ApiException e, String action) {
        int status = e.getResponseStatusCode();
        String detail = errorMessage(e);
        String message = switch (status) {
            case 429 -> "Microsoft Graph throttled the request while trying to " + action
                + " (HTTP 429" + retryAfter(e).map(r -> ", Retry-After: " + r + "s").orElse("")
                + "). Add a `retry` policy to the task to retry automatically.";
            case 401 -> "Authentication failed while trying to " + action + " (HTTP 401). Check tenantId, clientId and the client secret or certificate.";
            case 403 -> "Permission denied while trying to " + action + " (HTTP 403). Check that the app registration was granted the required "
                + "application permission (SecurityAlert.*, SecurityIncident.* or ThreatHunting.Read.All) with admin consent.";
            case 404 -> "Not found while trying to " + action + " (HTTP 404).";
            default -> "Microsoft Graph error while trying to " + action + " (HTTP " + status + ").";
        };
        if (detail != null) {
            message += " " + detail;
        }
        return new IllegalStateException(message, e);
    }

    static boolean isTransient(ApiException e) {
        int status = e.getResponseStatusCode();
        return status == 429 || status == 503 || status == 504;
    }

    private static String errorMessage(ApiException e) {
        if (e instanceof ODataError oDataError && oDataError.getError() != null) {
            String code = oDataError.getError().getCode();
            String msg = oDataError.getError().getMessage();
            return code != null ? code + ": " + msg : msg;
        }
        return e.getMessage();
    }

    private static java.util.Optional<String> retryAfter(ApiException e) {
        if (e.getResponseHeaders() == null) {
            return java.util.Optional.empty();
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
