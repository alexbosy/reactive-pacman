package org.coinen.reactive.pacman.metrics;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import io.micrometer.core.instrument.step.StepRegistryConfig;
import org.coinen.pacman.metrics.MetricsSnapshot;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

public class ReactiveMetricsRegistry extends StepMeterRegistry implements
                                                               InitializingBean,
                                                               DisposableBean {

    private FluxSink<MetricsSnapshot> snapshotFluxSink;

    public ReactiveMetricsRegistry(String prefix) {
        super(new StepRegistryConfig() {
            @Override
            public String prefix() {
                return prefix;
            }

            @Override
            public Duration step() {
                return Duration.ofSeconds(1);
            }

            @Override
            public String get(String key) {
                return null;
            }
        }, Clock.SYSTEM);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        start(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("Metrics-Worker");
            return thread;
        });
    }

    @Override
    public void destroy() throws Exception {
        stop();
    }

    public Flux<MetricsSnapshot> asFlux() {
        return Flux.create(s -> {
            synchronized (this) {
                if (this.snapshotFluxSink != null) {
                    System.out.println("Wow");
                    s.error(new IllegalStateException("Allowed only a single subscriber"));
                    return;
                }

                this.snapshotFluxSink = s;
            }

            s.onCancel(() -> {
                System.out.println("Cancelled");
                synchronized (this) {
                    this.snapshotFluxSink = null;
                }
            });

            s.onDispose(() -> {
                System.out.println("Disposed");
                synchronized (this) {
                    this.snapshotFluxSink = null;
                }
            });
        }, FluxSink.OverflowStrategy.IGNORE);
    }

    @Override
    protected void publish() {
        FluxSink<MetricsSnapshot> sink = snapshotFluxSink;

        if (sink != null && sink.requestedFromDownstream() > 0) {
            sink.next(
                getMeters()
                    .stream()
                    .reduce(
                        MetricsSnapshot.newBuilder(),
                        (ms, meter) -> ms.addMeters(MappingUtils.mapMeter(meter)),
                        (ms1, ms2) -> ms1.addAllMeters(ms2.getMetersList())
                    )
                    .build()
            );
        }
    }

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.NANOSECONDS;
    }
}
