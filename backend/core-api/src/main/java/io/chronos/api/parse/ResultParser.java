package io.chronos.api.parse;

import io.chronos.api.adapter.RawResult;
import java.util.List;

/**
 * Converts an adapter's {@link RawResult} into normalized {@link TagValue}s using the
 * Task's {@link MappingRule}s (§6). The core ships a default implementation; adapters may
 * provide a specialized parser when their raw shape needs it.
 */
@FunctionalInterface
public interface ResultParser {

    /**
     * @param raw      raw result returned by a {@code DeviceAdapter.collect()}
     * @param mappings mapping rules of the Task that produced {@code raw}
     * @return one {@link TagValue} per mapping rule; failures are reported via
     *         {@link Quality#BAD}/{@link Quality#UNCERTAIN} rather than thrown
     */
    List<TagValue> parse(RawResult raw, List<MappingRule> mappings);
}
