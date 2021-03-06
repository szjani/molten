/*
 * Copyright (c) 2020 Expedia, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hotels.molten.cache.resilience;

import static com.hotels.molten.core.metrics.MetricsSupport.GRAPHITE_ID;
import static com.hotels.molten.core.metrics.MetricsSupport.name;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.assertj.core.api.Assertions;
import org.mockito.Mock;
import org.mockito.testng.MockitoTestNGListener;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import com.hotels.molten.cache.ReactiveCache;
import com.hotels.molten.core.metrics.MoltenMetrics;
import com.hotels.molten.test.AssertSubscriber;

/**
 * Unit test for {@link ResilientReactiveCache}.
 */
@Listeners(MockitoTestNGListener.class)
public class ResilientReactiveCacheTest {
    private static final Long KEY = 1L;
    private static final String VALUE = "value";
    private static final String CACHE_NAME = "cacheName";
    private static final AtomicInteger IDX = new AtomicInteger();
    @Mock
    private ReactiveCache<Long, String> cache;
    private MeterRegistry meterRegistry;
    private VirtualTimeScheduler scheduler;
    private String cacheName;

    @BeforeMethod
    public void initContext() {
        MoltenMetrics.setDimensionalMetricsEnabled(false);
        meterRegistry = new SimpleMeterRegistry();
        scheduler = VirtualTimeScheduler.create();
        VirtualTimeScheduler.set(scheduler);
        cacheName = nextCacheName();
    }

    @AfterMethod
    public void clearContext() {
        MoltenMetrics.setDimensionalMetricsEnabled(false);
        VirtualTimeScheduler.reset();
    }

    @Test
    public void should_delegate_get_call() {
        when(cache.get(KEY)).thenReturn(Mono.just(VALUE));

        StepVerifier.create(getResilientCache(cacheName, 10, CircuitBreakerConfig.ofDefaults()).get(KEY))
            .expectNext(VALUE)
            .verifyComplete();
    }

    @Test
    public void should_timeout_get_call() {
        //When
        when(cache.get(KEY)).thenReturn(Mono.defer(() -> Mono.delay(Duration.ofMillis(15))).map(i -> VALUE));
        AssertSubscriber<String> test = AssertSubscriber.create();
        getResilientCache(cacheName, 10, CircuitBreakerConfig.ofDefaults()).get(KEY).subscribe(test);
        //Then
        scheduler.advanceTimeBy(Duration.ofMillis(20));
        test.assertError(TimeoutException.class);
        Assertions.assertThat(meterRegistry.get(name("reactive-cache", cacheName, "get", "timeout")).gauge().value()).isEqualTo(1);
    }

    @Test
    public void should_register_dimensional_metric_for_timeout() {
        //Given
        MoltenMetrics.setDimensionalMetricsEnabled(true);
        MoltenMetrics.setGraphiteIdMetricsLabelEnabled(true);
        //When
        when(cache.get(KEY)).thenReturn(Mono.defer(() -> Mono.delay(Duration.ofMillis(15))).map(i -> VALUE));
        AssertSubscriber<String> test = AssertSubscriber.create();
        getResilientCache(cacheName, 10, CircuitBreakerConfig.ofDefaults()).get(KEY).subscribe(test);
        //Then
        scheduler.advanceTimeBy(Duration.ofMillis(20));
        test.assertError(TimeoutException.class);
        Assertions.assertThat(meterRegistry.get("cache_request_timeouts")
            .tag("name", cacheName)
            .tag("operation", "get")
            .tag(GRAPHITE_ID, name("reactive-cache", cacheName, "get", "timeout"))
            .gauge().value()).isEqualTo(1);
    }

    @Test
    public void should_break_circuit_of_get_call() {
        AtomicInteger subscriptionCount = new AtomicInteger();
        when(cache.get(KEY)).thenReturn(Mono.create(e -> {
            subscriptionCount.incrementAndGet();
            e.error(new IllegalStateException());
        }));

        CircuitBreakerConfig cb = CircuitBreakerConfig.custom().failureRateThreshold(0.5F)
            .slidingWindow(2, 2, CircuitBreakerConfig.SlidingWindowType.COUNT_BASED).permittedNumberOfCallsInHalfOpenState(2).build();
        ResilientReactiveCache<Long, String> resilientCache = getResilientCache(cacheName, 10, cb);

        AssertSubscriber<String> test = AssertSubscriber.create();
        resilientCache.get(KEY).subscribe(test);
        test.assertError(IllegalStateException.class);
        test = AssertSubscriber.create();
        resilientCache.get(KEY).subscribe(test);
        test.assertError(IllegalStateException.class);
        test = AssertSubscriber.create();
        resilientCache.get(KEY).subscribe(test);
        test.assertError(CallNotPermittedException.class);
        test = AssertSubscriber.create();
        resilientCache.get(KEY).subscribe(test);
        test.assertError(CallNotPermittedException.class);

        assertThat(subscriptionCount.get(), is(2));
    }

    @Test
    public void should_delegate_put_call() {
        when(cache.put(KEY, VALUE)).thenReturn(Mono.empty());

        StepVerifier.create(getResilientCache(cacheName, 10, CircuitBreakerConfig.ofDefaults()).put(KEY, VALUE)).verifyComplete();

        verify(cache).put(KEY, VALUE);
    }

    @Test
    public void should_timeout_put_call() {
        //When
        when(cache.put(KEY, VALUE)).thenReturn(Mono.defer(() -> Mono.delay(Duration.ofMillis(15))).then());
        //Then
        AssertSubscriber<Void> test = AssertSubscriber.create();
        getResilientCache(cacheName, 10, CircuitBreakerConfig.ofDefaults()).put(KEY, VALUE).subscribe(test);
        scheduler.advanceTimeBy(Duration.ofMillis(20));
        test.assertError(TimeoutException.class);
        Assertions.assertThat(meterRegistry.get(name("reactive-cache", cacheName, "put", "timeout")).gauge().value()).isEqualTo(1);
    }

    @Test
    public void should_break_circuit_of_put_call() {
        AtomicInteger subscriptionCount = new AtomicInteger();
        when(cache.put(KEY, VALUE)).thenReturn(Mono.create(e -> {
            subscriptionCount.incrementAndGet();
            e.error(new IllegalStateException());
        }));

        CircuitBreakerConfig cb = CircuitBreakerConfig.custom().failureRateThreshold(0.5F)
            .slidingWindow(2, 2, CircuitBreakerConfig.SlidingWindowType.COUNT_BASED).permittedNumberOfCallsInHalfOpenState(2).build();
        ResilientReactiveCache<Long, String> resilientCache = getResilientCache(cacheName, 10, cb);

        AssertSubscriber<Void> test = AssertSubscriber.create();
        resilientCache.put(KEY, VALUE).subscribe(test);
        test.assertError(IllegalStateException.class);
        test = AssertSubscriber.create();
        resilientCache.put(KEY, VALUE).subscribe(test);
        test.assertError(IllegalStateException.class);
        test = AssertSubscriber.create();
        resilientCache.put(KEY, VALUE).subscribe(test);
        test.assertError(CallNotPermittedException.class);
        test = AssertSubscriber.create();
        resilientCache.put(KEY, VALUE).subscribe(test);
        test.assertError(CallNotPermittedException.class);

        assertThat(subscriptionCount.get(), is(2));
    }

    @Test
    public void should_have_common_circuit_for_get_and_put() {
        // When
        when(cache.put(KEY, VALUE)).thenReturn(Mono.error(new IllegalStateException()));
        when(cache.get(KEY)).thenReturn(Mono.just(VALUE));
        CircuitBreakerConfig cb = CircuitBreakerConfig.custom().failureRateThreshold(0.5F)
            .slidingWindow(2, 2, CircuitBreakerConfig.SlidingWindowType.COUNT_BASED).permittedNumberOfCallsInHalfOpenState(2).build();
        ResilientReactiveCache<Long, String> resilientCache = getResilientCache(cacheName, 10, cb);
        // Then
        AssertSubscriber<Void> test = AssertSubscriber.create();
        resilientCache.put(KEY, VALUE).subscribe(test);
        test.assertError(IllegalStateException.class);
        test = AssertSubscriber.create();
        resilientCache.put(KEY, VALUE).subscribe(test);
        test.assertError(IllegalStateException.class);
        test = AssertSubscriber.create();
        resilientCache.put(KEY, VALUE).subscribe(test);
        test.assertError(CallNotPermittedException.class);
        AssertSubscriber<String> test2 = AssertSubscriber.create();
        resilientCache.get(KEY).subscribe(test2);
        test.assertError(CallNotPermittedException.class);
        Assertions.assertThat(meterRegistry.get("reactive-cache." + cacheName + ".circuit." + "successful").gauge().value()).isEqualTo(0D);
        Assertions.assertThat(meterRegistry.get("reactive-cache." + cacheName + ".circuit." + "failed").gauge().value()).isEqualTo(2D);
        Assertions.assertThat(meterRegistry.get("reactive-cache." + cacheName + ".circuit." + "rejected").gauge().value()).isEqualTo(2D);
    }

    private String nextCacheName() {
        return CACHE_NAME + IDX.incrementAndGet();
    }

    private ResilientReactiveCache<Long, String> getResilientCache(String cacheName, int timeoutInMs, CircuitBreakerConfig circuitBreakerConfig) {
        return new ResilientReactiveCache<>(this.cache, cacheName, Duration.ofMillis(timeoutInMs), circuitBreakerConfig, meterRegistry);
    }
}
