package io.chronos.api.parse;

import java.util.Map;

/**
 * Rule that extracts one Tag value from a {@code RawResult} (§6).
 *
 * @param tagId     target Tag id
 * @param tagKey    canonical tag key the produced {@link TagValue} carries
 * @param extractor extraction spec, e.g. {@code {"kind":"COLUMN","expr":"temp_c"}} or
 *                  {@code {"kind":"JSONPATH","expr":"$.data.temp"}} (kinds: COLUMN/JSONPATH/REGEX/EXPR)
 * @param transform optional post-processing, e.g. {@code {"cast":"NUMBER","scale":0.1,"offset":0}}
 */
public record MappingRule(
        String tagId,
        String tagKey,
        Map<String, Object> extractor,
        Map<String, Object> transform) {

    public MappingRule {
        extractor = extractor == null ? Map.of() : Map.copyOf(extractor);
        transform = transform == null ? Map.of() : Map.copyOf(transform);
    }
}
