package io.chronos.app.config;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the OTLP <em>span</em> exporter (§11). Spring Boot 4 modularized observability and ships no
 * OTLP trace-exporter auto-config (only SDK + OTLP-logging + the Micrometer bridge), so we build the
 * {@link SdkTracerProvider} that {@code OpenTelemetrySdkAutoConfiguration} assembles into the SDK.
 * Active only when {@code management.tracing.enabled=true} (i.e. OTEL_ENABLED=true).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "management.tracing.enabled", havingValue = "true")
public class OtelTracingConfig {

    @Bean
    SdkTracerProvider sdkTracerProvider(
            @Value("${spring.application.name:chronos}") String serviceName,
            @Value("${management.otlp.tracing.endpoint:http://localhost:4318/v1/traces}") String endpoint,
            @Value("${management.tracing.sampling.probability:1.0}") double samplingProbability) {
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .build();
        Resource resource = Resource.getDefault().merge(Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), serviceName)));
        return SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(samplingProbability)))
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();
    }

    // Boot 4 ships no OTel→Micrometer bridge auto-config, so build the Micrometer Tracer + Propagator
    // from the OpenTelemetry SDK ourselves (otherwise NoopTracerAutoConfiguration wins → no spans).
    @Bean
    @ConditionalOnMissingBean
    Tracer micrometerTracer(OpenTelemetry openTelemetry) {
        io.opentelemetry.api.trace.Tracer otelTracer = openTelemetry.getTracer("io.chronos");
        return new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> { });
    }

    @Bean
    @ConditionalOnMissingBean
    Propagator micrometerPropagator(OpenTelemetry openTelemetry) {
        return new OtelPropagator(openTelemetry.getPropagators(), openTelemetry.getTracer("io.chronos"));
    }
}
