package io.chronos.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.chronos.api.adapter.RawResult;
import io.chronos.api.parse.MappingRule;
import io.chronos.api.parse.Quality;
import io.chronos.api.parse.TagValue;
import io.chronos.engine.parse.DefaultResultParser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultResultParserTest {

    private final DefaultResultParser parser = new DefaultResultParser();

    private static RawResult oneRow(Map<String, Object> row) {
        return RawResult.ofRows(List.of(row), Instant.EPOCH);
    }

    @Test
    void extractsColumnValue() {
        var rule = new MappingRule("t1", "n.temp", Map.of("kind", "COLUMN", "expr", "temp_c"), Map.of());
        List<TagValue> out = parser.parse(oneRow(Map.of("temp_c", 21.5)), List.of(rule));
        assertEquals(1, out.size());
        assertEquals(21.5, out.get(0).value());
        assertEquals(Quality.GOOD, out.get(0).quality());
    }

    @Test
    void appliesScaleAndOffsetTransform() {
        var rule = new MappingRule("t1", "n.temp", Map.of("kind", "COLUMN", "expr", "raw"),
                Map.of("cast", "NUMBER", "scale", 0.1, "offset", 5));
        List<TagValue> out = parser.parse(oneRow(Map.of("raw", 100)), List.of(rule));
        assertEquals(15.0, (double) out.get(0).value(), 1e-9); // 100*0.1 + 5
    }

    @Test
    void missingColumnIsBadQuality() {
        var rule = new MappingRule("t1", "n.temp", Map.of("kind", "COLUMN", "expr", "nope"), Map.of());
        List<TagValue> out = parser.parse(oneRow(Map.of("temp_c", 21.5)), List.of(rule));
        assertEquals(Quality.BAD, out.get(0).quality());
    }

    @Test
    void columnLookupIsCaseInsensitive() {
        var rule = new MappingRule("t1", "n.temp", Map.of("kind", "COLUMN", "expr", "TEMP_C"), Map.of());
        List<TagValue> out = parser.parse(oneRow(Map.of("temp_c", 7)), List.of(rule));
        assertEquals(7, out.get(0).value());
    }

    @Test
    void extractsViaJsonPath() {
        var raw = RawResult.ofJson("{\"data\":{\"temp\":21.5}}", Instant.EPOCH);
        var rule = new MappingRule("t1", "n.temp", Map.of("kind", "JSONPATH", "expr", "$.data.temp"), Map.of());
        List<TagValue> out = parser.parse(raw, List.of(rule));
        assertEquals(21.5, out.get(0).value());
        assertEquals(Quality.GOOD, out.get(0).quality());
    }

    @Test
    void extractsViaRegexCaptureGroup() {
        var raw = RawResult.ofText("temp=21.5C humidity=47%", Instant.EPOCH);
        var rule = new MappingRule("t1", "n.temp",
                Map.of("kind", "REGEX", "expr", "temp=([0-9.]+)"),
                Map.of("cast", "NUMBER"));
        List<TagValue> out = parser.parse(raw, List.of(rule));
        assertEquals(21.5, (double) out.get(0).value(), 1e-9);
    }

    @Test
    void missingJsonPathIsBadQuality() {
        var raw = RawResult.ofJson("{\"data\":{}}", Instant.EPOCH);
        var rule = new MappingRule("t1", "n.temp", Map.of("kind", "JSONPATH", "expr", "$.data.temp"), Map.of());
        assertEquals(Quality.BAD, parser.parse(raw, List.of(rule)).get(0).quality());
    }

    @Test
    void constantExtractorEmitsFixedValue() {
        var rule = new MappingRule("t1", "n.k", Map.of("kind", "CONSTANT", "value", 42.0), Map.of());
        var out = parser.parse(RawResult.ofText("", Instant.EPOCH), List.of(rule));
        assertEquals(42.0, out.get(0).value());
        assertEquals(Quality.GOOD, out.get(0).quality());
    }

    @Test
    void templateExtractorComposesFromColumns() {
        var rule = new MappingRule("t1", "n.id",
                Map.of("kind", "TEMPLATE", "expr", "${line}/${cell}"), Map.of());
        var out = parser.parse(oneRow(Map.of("line", "L1", "cell", "C7")), List.of(rule));
        assertEquals("L1/C7", out.get(0).value());
    }

    @Test
    void indexExtractorPicksNthRow() {
        var raw = RawResult.ofRows(
                List.of(Map.of("v", 10), Map.of("v", 20), Map.of("v", 30)), Instant.EPOCH);
        var rule = new MappingRule("t1", "n.v", Map.of("kind", "INDEX", "row", 2, "expr", "v"), Map.of());
        assertEquals(30, parser.parse(raw, List.of(rule)).get(0).value());
    }

    @Test
    void roundAndClampTransforms() {
        var rule = new MappingRule("t1", "n.v", Map.of("kind", "COLUMN", "expr", "v"),
                Map.of("cast", "NUMBER", "round", 1, "max", 9.0));
        var out = parser.parse(oneRow(Map.of("v", 12.3456)), List.of(rule));
        assertEquals(9.0, (double) out.get(0).value(), 1e-9); // rounded to 12.3 then clamped to 9.0
    }

    @Test
    void lookupTransformMapsValue() {
        var rule = new MappingRule("t1", "n.state", Map.of("kind", "COLUMN", "expr", "s"),
                Map.of("lookup", Map.of("1", "ON", "0", "OFF")));
        var out = parser.parse(oneRow(Map.of("s", 1)), List.of(rule));
        assertEquals("ON", out.get(0).value());
    }

    @Test
    void defaultTransformRescuesMissingValue() {
        var rule = new MappingRule("t1", "n.v", Map.of("kind", "COLUMN", "expr", "nope"),
                Map.of("default", -1));
        var out = parser.parse(oneRow(Map.of("v", 5)), List.of(rule));
        assertEquals(-1, out.get(0).value());
        assertEquals(Quality.GOOD, out.get(0).quality());
    }
}
