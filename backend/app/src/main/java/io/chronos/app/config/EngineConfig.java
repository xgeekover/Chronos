package io.chronos.app.config;

import io.chronos.api.history.HistoryStore;
import io.chronos.api.parse.ResultParser;
import io.chronos.engine.broadcast.TagUpdateListener;
import io.chronos.engine.cache.CurrentValueCache;
import io.chronos.engine.history.SqliteHistoryStore;
import io.chronos.engine.parse.DefaultResultParser;
import io.chronos.engine.pipeline.CollectionPipeline;
import io.chronos.engine.plugin.CompositePluginRuntime;
import io.chronos.engine.plugin.Pf4jPluginRuntime;
import io.chronos.engine.plugin.PluginRuntime;
import io.chronos.engine.plugin.ServiceLoaderPluginRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the Spring-free collection engine into the application context (§7). */
@Configuration
public class EngineConfig {

    private static final Logger log = LoggerFactory.getLogger(EngineConfig.class);

    @Bean
    PluginRuntime pluginRuntime(@Value("${chronos.plugins.dir:plugins}") String pluginsDir) {
        // Built-in adapters (classpath, ServiceLoader) + externally dropped PF4J plugin JARs.
        List<PluginRuntime> delegates = new ArrayList<>();
        delegates.add(ServiceLoaderPluginRuntime.fromContextClasspath());

        Path dir = Path.of(pluginsDir);
        if (Files.isDirectory(dir)) {
            delegates.add(new Pf4jPluginRuntime(dir)); // drop a JAR here → new protocol, no core change
            log.info("Scanning PF4J plugin directory: {}", dir.toAbsolutePath());
        }

        PluginRuntime runtime = new CompositePluginRuntime(delegates);
        log.info("Discovered {} device adapter(s): {}", runtime.all().size(),
                runtime.all().stream().map(a -> a.type()).sorted().toList());
        return runtime;
    }

    @Bean
    ResultParser resultParser() {
        return new DefaultResultParser();
    }

    @Bean(destroyMethod = "close")
    HistoryStore historyStore(@Value("${chronos.history.data-dir:data/history}") String dataDir) {
        return new SqliteHistoryStore(Path.of(dataDir));
    }

    @Bean
    CurrentValueCache currentValueCache() {
        return new CurrentValueCache();
    }

    /** Bounded platform-thread pool for I/O-bound collection (ADR-010; no virtual threads on Java 17). */
    @Bean(destroyMethod = "shutdown")
    ExecutorService collectionExecutor(@Value("${chronos.collection.pool-size:16}") int poolSize) {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "chronos-collect-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    CollectionPipeline collectionPipeline(
            PluginRuntime pluginRuntime,
            ResultParser resultParser,
            HistoryStore historyStore,
            CurrentValueCache currentValueCache,
            TagUpdateListener broadcast, // GatewayBroadcaster — pushes updates to SDK clients (§9)
            ExecutorService collectionExecutor) {
        return new CollectionPipeline(pluginRuntime, resultParser, historyStore,
                currentValueCache, broadcast, collectionExecutor);
    }
}
