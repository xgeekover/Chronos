package io.chronos.engine.pipeline;

import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceAdapter;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.history.HistoryStore;
import io.chronos.api.history.TagSample;
import io.chronos.api.parse.MappingRule;
import io.chronos.api.parse.ResultParser;
import io.chronos.api.parse.TagValue;
import io.chronos.engine.broadcast.TagUpdateListener;
import io.chronos.engine.cache.CurrentValueCache;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The adapter-agnostic collection pipeline (§7): resolve adapter → collect (with external
 * timeout) → parse to TagValues → fan out to current-value cache + node history + broadcast.
 * Identical for every adapter kind (불변 규칙 3).
 */
public final class CollectionPipeline {

    private final io.chronos.engine.plugin.PluginRuntime plugins;
    private final ResultParser parser;
    private final HistoryStore history;
    private final CurrentValueCache cache;
    private final TagUpdateListener broadcast;
    private final ExecutorService executor;

    public CollectionPipeline(
            io.chronos.engine.plugin.PluginRuntime plugins,
            ResultParser parser,
            HistoryStore history,
            CurrentValueCache cache,
            TagUpdateListener broadcast,
            ExecutorService executor) {
        this.plugins = plugins;
        this.parser = parser;
        this.history = history;
        this.cache = cache;
        this.broadcast = broadcast;
        this.executor = executor;
    }

    public CollectionResult run(DeviceConfig config, CollectRequest request, List<MappingRule> mappings) {
        long start = System.nanoTime();
        DeviceAdapter adapter = plugins.find(config.adapterType()).orElse(null);
        if (adapter == null) {
            return error(start, "no adapter for type " + config.adapterType());
        }

        RawResult raw;
        Future<RawResult> future = executor.submit(() -> adapter.collect(config, request));
        try {
            raw = future.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new CollectionResult(CollectionResult.Status.TIMEOUT, 0, elapsedMs(start),
                    "collect timed out after " + request.timeout().toMillis() + "ms", List.of());
        } catch (Exception e) {
            future.cancel(true);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return error(start, cause.getMessage());
        }

        List<TagValue> values = parser.parse(raw, mappings);
        ingest(request.nodeId(), values);
        return new CollectionResult(CollectionResult.Status.OK, values.size(), elapsedMs(start), null, values);
    }

    /**
     * Persist + propagate tag values (the §7 step-4 fan-out: current-value cache + node-local history +
     * gateway broadcast). Shared by scheduled collection ({@link #run}) and out-of-band writers such as
     * the flow runtime's historian-write node, so there's one authoritative place for the fan-out.
     * Alarm evaluation lives in the app layer (it needs the tag/alarm repositories) and is applied there.
     */
    public void ingest(String nodeId, List<TagValue> values) {
        values.forEach(cache::put);
        List<TagSample> samples = values.stream()
                .map(v -> new TagSample(v.tagKey(), v.value(), v.quality(), v.ts().toEpochMilli()))
                .toList();
        history.append(nodeId, samples);
        broadcast.onUpdates(nodeId, values);
    }

    /**
     * Run an adapter once and return the RAW result (no parse/ingest) with the same bounded executor +
     * timeout as {@link #run}. Used by the flow runtime's "device read" node so a flow can pull from any
     * adapter (JDBC/Modbus/shell/file/api/script) on demand. Throws on missing adapter / timeout / error.
     */
    public RawResult readOnce(DeviceConfig config, CollectRequest request) {
        DeviceAdapter adapter = plugins.find(config.adapterType())
                .orElseThrow(() -> new IllegalArgumentException("no adapter for type " + config.adapterType()));
        Future<RawResult> future = executor.submit(() -> adapter.collect(config, request));
        try {
            return future.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("adapter read timed out after " + request.timeout().toMillis() + "ms");
        } catch (Exception e) {
            future.cancel(true);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(cause.getMessage() == null ? cause.toString() : cause.getMessage(), cause);
        }
    }

    private CollectionResult error(long start, String message) {
        return new CollectionResult(CollectionResult.Status.ERROR, 0, elapsedMs(start), message, List.of());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
