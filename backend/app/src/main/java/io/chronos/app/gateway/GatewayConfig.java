package io.chronos.app.gateway;

import io.chronos.engine.broadcast.TagUpdateListener;
import io.chronos.gateway.FrameCodec;
import io.chronos.gateway.GatewayBroadcaster;
import io.chronos.gateway.SubscriptionManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Gateway collaborators as beans (§9). The WebSocket endpoint is registered in {@link GatewayWebSocketConfig}. */
@Configuration
public class GatewayConfig {

    @Bean
    SubscriptionManager subscriptionManager(MeterRegistry meters) {
        SubscriptionManager subs = new SubscriptionManager();
        Gauge.builder("chronos.gateway.subscriptions", subs, s -> s.all().size())
                .description("active SDK subscriptions")
                .register(meters);
        return subs;
    }

    @Bean
    FrameCodec frameCodec(@Value("${chronos.gateway.compress:true}") boolean compress) {
        return new FrameCodec(compress);
    }

    /** The pipeline's broadcast sink — picked up by EngineConfig's CollectionPipeline. */
    @Bean
    TagUpdateListener gatewayBroadcaster(SubscriptionManager subs, FrameCodec codec) {
        return new GatewayBroadcaster(subs, codec);
    }
}
