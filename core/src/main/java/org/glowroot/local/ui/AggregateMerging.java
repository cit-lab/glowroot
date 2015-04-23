/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.local.ui;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.io.CharSource;
import org.immutables.value.Value;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.LazyHistogram;
import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregateTimer;
import org.glowroot.transaction.model.ProfileNode;

public class AggregateMerging {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private AggregateMerging() {}

    public static TimerMergedAggregate getTimerMergedAggregate(List<Aggregate> aggregates)
            throws Exception {
        long transactionCount = 0;
        AggregateTimer syntheticRootTimer = AggregateTimer.createSyntheticRootTimer();
        for (Aggregate aggregate : aggregates) {
            transactionCount += aggregate.transactionCount();
            AggregateTimer toBeMergedSyntheticRootTimer =
                    mapper.readValue(aggregate.timers(), AggregateTimer.class);
            syntheticRootTimer.mergeMatchedTimer(toBeMergedSyntheticRootTimer);
        }
        TimerMergedAggregate.Builder timerMergedAggregate = TimerMergedAggregate.builder();
        timerMergedAggregate.syntheticRootTimer(syntheticRootTimer);
        timerMergedAggregate.transactionCount(transactionCount);
        return timerMergedAggregate.build();
    }

    public static HistogramMergedAggregate getHistogramMergedAggregate(List<Aggregate> aggregates)
            throws Exception {
        long transactionCount = 0;
        long totalMicros = 0;
        LazyHistogram histogram = new LazyHistogram();
        for (Aggregate aggregate : aggregates) {
            transactionCount += aggregate.transactionCount();
            totalMicros += aggregate.totalMicros();
            histogram.decodeFromByteBuffer(ByteBuffer.wrap(aggregate.histogram()));
        }
        return HistogramMergedAggregate.builder()
                .histogram(histogram)
                .totalMicros(totalMicros)
                .transactionCount(transactionCount)
                .build();
    }

    public static ThreadInfoAggregate getThreadInfoAggregate(List<Aggregate> aggregates) {
        Long totalCpuMicros = null;
        Long totalBlockedMicros = null;
        Long totalWaitedMicros = null;
        Long totalAllocatedKBytes = null;
        for (Aggregate aggregate : aggregates) {
            totalCpuMicros = nullAwareAdd(totalCpuMicros, aggregate.totalCpuMicros());
            totalBlockedMicros = nullAwareAdd(totalBlockedMicros, aggregate.totalBlockedMicros());
            totalWaitedMicros = nullAwareAdd(totalWaitedMicros, aggregate.totalWaitedMicros());
            totalAllocatedKBytes = nullAwareAdd(totalAllocatedKBytes,
                    aggregate.totalAllocatedKBytes());
        }
        return ThreadInfoAggregate.builder()
                .totalCpuMicros(totalCpuMicros)
                .totalBlockedMicros(totalBlockedMicros)
                .totalWaitedMicros(totalWaitedMicros)
                .totalAllocatedKBytes(totalAllocatedKBytes)
                .build();
    }

    public static ProfileNode getProfile(List<CharSource> profiles) throws IOException {
        ProfileNode syntheticRootNode = ProfileNode.createSyntheticRoot();
        for (CharSource profile : profiles) {
            String profileContent = profile.read();
            if (profileContent.equals(AggregateDao.OVERWRITTEN)) {
                continue;
            }
            ProfileNode toBeMergedRootNode =
                    ObjectMappers.readRequiredValue(mapper, profileContent, ProfileNode.class);
            syntheticRootNode.mergeMatchedNode(toBeMergedRootNode);
        }
        return syntheticRootNode;
    }

    private static @Nullable Long nullAwareAdd(@Nullable Long x, @Nullable Long y) {
        if (x == null) {
            return y;
        }
        if (y == null) {
            return x;
        }
        return x + y;
    }

    @Value.Immutable
    @JsonSerialize
    public static abstract class TimerMergedAggregateBase {
        @JsonProperty("timers")
        public abstract AggregateTimer syntheticRootTimer();
        public abstract long transactionCount();
    }

    @Value.Immutable
    @JsonSerialize
    public static abstract class HistogramMergedAggregateBase {

        @JsonIgnore
        public abstract LazyHistogram histogram();
        public abstract long totalMicros();
        public abstract long transactionCount();

        @Value.Derived
        public long percentile1() {
            return histogram().getValueAtPercentile(50);
        }

        @Value.Derived
        public long percentile2() {
            return histogram().getValueAtPercentile(95);
        }

        @Value.Derived
        public long percentile3() {
            return histogram().getValueAtPercentile(99);
        }
    }

    @Value.Immutable
    @JsonSerialize
    public abstract static class ThreadInfoAggregateBase {

        abstract @Nullable Long totalCpuMicros();
        abstract @Nullable Long totalBlockedMicros();
        abstract @Nullable Long totalWaitedMicros();
        abstract @Nullable Long totalAllocatedKBytes();

        public boolean isEmpty() {
            return totalCpuMicros() == null && totalBlockedMicros() == null
                    && totalWaitedMicros() == null && totalAllocatedKBytes() == null;
        }
    }
}