package com.edwardjones.mcp.edge.config;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Registers a real OpenTelemetry SDK for local development so that spans
 * have valid trace/span IDs and trace context propagation works end-to-end.
 * Spans are logged to stdout via LoggingSpanExporter.
 *
 * In production, Dynatrace OneAgent registers its own SDK — this config
 * is skipped because the 'local' profile is not active.
 */
@Configuration
@Profile("local")
public class LocalOpenTelemetryConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalOpenTelemetryConfig.class);

    private SdkTracerProvider tracerProvider;

    @PostConstruct
    public void init() {
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                .build();

        OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(
                        TextMapPropagator.composite(
                                W3CTraceContextPropagator.getInstance(),
                                W3CBaggagePropagator.getInstance())))
                .buildAndRegisterGlobal();

        log.info("Local OpenTelemetry SDK registered with LoggingSpanExporter");
    }

    @PreDestroy
    public void shutdown() {
        if (tracerProvider != null) {
            tracerProvider.close();
        }
    }
}
