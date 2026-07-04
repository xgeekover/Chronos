package io.chronos.engine.parse;

import com.jayway.jsonpath.JsonPath;
import io.chronos.api.adapter.RawKind;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.parse.MappingRule;
import io.chronos.api.parse.Quality;
import io.chronos.api.parse.ResultParser;
import io.chronos.api.parse.TagValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core {@link ResultParser} (§7 step 3).
 *
 * <p>Extractors: {@code COLUMN}/{@code INDEX} (tabular rows, JDBC path), {@code JSONPATH} and
 * {@code REGEX} (text payloads), {@code TEMPLATE} (compose a string from row columns), and
 * {@code CONSTANT} (a fixed value, source-independent).
 *
 * <p>Transforms (applied in order): {@code cast} → {@code scale}/{@code offset} → {@code round}
 * → {@code clamp} (min/max) → {@code lookup} (value→replacement map); {@code default} rescues a
 * null extraction. Mapping/transform failures are reported as {@link Quality#BAD}/
 * {@link Quality#UNCERTAIN} rather than thrown, so one bad tag never fails the whole collection.
 */
public final class DefaultResultParser implements ResultParser {

    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\$\\{([^}]+)}");

    @Override
    public List<TagValue> parse(RawResult raw, List<MappingRule> mappings) {
        List<TagValue> out = new ArrayList<>(mappings.size());
        var ts = raw.collectedAt();
        for (MappingRule rule : mappings) {
            String kind = String.valueOf(rule.extractor().getOrDefault("kind", "COLUMN"));
            try {
                Object extracted = switch (kind.toUpperCase()) {
                    case "COLUMN" -> extractColumn(rowAt(raw, 0), rule);
                    case "INDEX" -> extractIndex(raw, rule);
                    case "JSONPATH" -> extractJsonPath(raw, rule);
                    case "REGEX" -> extractRegex(raw, rule);
                    case "TEMPLATE" -> extractTemplate(raw, rule);
                    case "CONSTANT" -> rule.extractor().get("value");
                    default -> throw new UnsupportedOperationException("unknown extractor kind '" + kind + "'");
                };
                if (extracted == null) {
                    Object dflt = rule.transform().get("default");
                    out.add(dflt != null
                            ? new TagValue(rule.tagKey(), dflt, Quality.GOOD, ts)
                            : new TagValue(rule.tagKey(), null, Quality.BAD, ts));
                    continue;
                }
                TransformResult tr = applyTransform(extracted, rule.transform());
                out.add(new TagValue(rule.tagKey(), tr.value(), tr.quality(), ts));
            } catch (RuntimeException e) {
                out.add(new TagValue(rule.tagKey(), null, Quality.BAD, ts));
            }
        }
        return out;
    }

    private static Map<String, Object> rowAt(RawResult raw, int idx) {
        if (raw.kind() == RawKind.ROWS && idx >= 0 && idx < raw.rows().size()) {
            return raw.rows().get(idx);
        }
        return Map.of();
    }

    private static Object extractJsonPath(RawResult raw, MappingRule rule) {
        if (raw.text() == null) {
            return null;
        }
        String path = String.valueOf(rule.extractor().get("expr"));
        return JsonPath.read(raw.text(), path); // PathNotFound → caught upstream → BAD
    }

    private static Object extractRegex(RawResult raw, MappingRule rule) {
        if (raw.text() == null) {
            return null;
        }
        Matcher m = Pattern.compile(String.valueOf(rule.extractor().get("expr"))).matcher(raw.text());
        if (!m.find()) {
            return null;
        }
        return m.groupCount() >= 1 ? m.group(1) : m.group(0); // first capture group, else whole match
    }

    private static Object extractColumn(Map<String, Object> row, MappingRule rule) {
        String column = String.valueOf(rule.extractor().get("expr"));
        if (row.containsKey(column)) {
            return row.get(column);
        }
        // case-insensitive fallback (DBs vary on column-name casing)
        for (var e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(column)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Pick row N (extractor "row"), then a column (extractor "expr") or the first value if blank. */
    private static Object extractIndex(RawResult raw, MappingRule rule) {
        int idx = (int) toDouble(rule.extractor().getOrDefault("row", 0));
        Map<String, Object> row = rowAt(raw, idx);
        Object expr = rule.extractor().get("expr");
        if (expr == null || String.valueOf(expr).isBlank()) {
            return row.isEmpty() ? null : row.values().iterator().next();
        }
        return extractColumn(row, rule);
    }

    /** Fill {@code ${column}} placeholders from the first row to compose a string value. */
    private static Object extractTemplate(RawResult raw, MappingRule rule) {
        Map<String, Object> row = rowAt(raw, 0);
        Matcher m = TEMPLATE_VAR.matcher(String.valueOf(rule.extractor().get("expr")));
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = row.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private record TransformResult(Object value, Quality quality) {}

    private static TransformResult applyTransform(Object value, Map<String, Object> transform) {
        if (transform == null || transform.isEmpty()) {
            return new TransformResult(value, Quality.GOOD);
        }
        try {
            Object v = transform.containsKey("cast")
                    ? cast(value, String.valueOf(transform.get("cast")))
                    : value;
            if (v instanceof Number n && (transform.containsKey("scale") || transform.containsKey("offset"))) {
                v = n.doubleValue() * toDouble(transform.getOrDefault("scale", 1))
                        + toDouble(transform.getOrDefault("offset", 0));
            }
            if (v instanceof Number n && transform.containsKey("round")) {
                double f = Math.pow(10, (int) toDouble(transform.get("round")));
                v = Math.round(n.doubleValue() * f) / f;
            }
            if (v instanceof Number n && (transform.containsKey("min") || transform.containsKey("max"))) {
                double d = n.doubleValue();
                if (transform.containsKey("min")) {
                    d = Math.max(d, toDouble(transform.get("min")));
                }
                if (transform.containsKey("max")) {
                    d = Math.min(d, toDouble(transform.get("max")));
                }
                v = d;
            }
            if (transform.get("lookup") instanceof Map<?, ?> lut) {
                Object hit = lut.get(String.valueOf(v));
                if (hit != null) {
                    v = hit;
                }
            }
            return new TransformResult(v, Quality.GOOD);
        } catch (RuntimeException e) {
            // value present but transform failed → keep raw value, flag uncertain
            return new TransformResult(value, Quality.UNCERTAIN);
        }
    }

    private static Object cast(Object value, String cast) {
        return switch (cast.toUpperCase()) {
            case "NUMBER" -> value instanceof Number n ? n : Double.valueOf(String.valueOf(value));
            case "STRING" -> String.valueOf(value);
            case "BOOL" -> value instanceof Boolean b ? b : Boolean.valueOf(String.valueOf(value));
            default -> value;
        };
    }

    private static double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o));
    }
}
