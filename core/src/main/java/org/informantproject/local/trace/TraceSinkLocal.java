/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.local.trace;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.informantproject.api.Message;
import org.informantproject.core.config.ConfigService;
import org.informantproject.core.config.CoreConfig;
import org.informantproject.core.trace.Span;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.Trace.TraceAttribute;
import org.informantproject.core.trace.TraceSink;
import org.informantproject.core.util.DaemonExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Implementation of TraceSink for local storage in embedded H2 database. Some day there may be
 * another implementation for remote storage (e.g. central monitoring system).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceSinkLocal implements TraceSink {

    private static final Logger logger = LoggerFactory.getLogger(TraceSinkLocal.class);

    private final ExecutorService executorService = DaemonExecutors
            .newSingleThreadExecutor("Informant-StackCollector");

    private final ConfigService configService;
    private final TraceDao traceDao;
    private final TraceCommonJsonService traceCommonJsonService;
    private final Ticker ticker;
    private final AtomicInteger queueLength = new AtomicInteger(0);
    private final Gson gson = new Gson();

    @Inject
    TraceSinkLocal(ConfigService configService, TraceDao traceDao,
            TraceCommonJsonService traceCommonJsonService, Ticker ticker) {

        this.configService = configService;
        this.traceDao = traceDao;
        this.traceCommonJsonService = traceCommonJsonService;
        this.ticker = ticker;
    }

    public void onCompletedTrace(final Trace trace) {
        CoreConfig config = configService.getCoreConfig();
        int thresholdMillis = config.getThresholdMillis();
        boolean thresholdDisabled = (thresholdMillis == CoreConfig.THRESHOLD_DISABLED);
        long durationInNanoseconds = trace.getRootSpan().getDuration();

        if ((!thresholdDisabled && durationInNanoseconds >= TimeUnit.MILLISECONDS
                .toNanos(thresholdMillis)) || trace.isStuck() || trace.isError()) {

            queueLength.incrementAndGet();
            executorService.execute(new Runnable() {
                public void run() {
                    try {
                        traceDao.storeTrace(buildStoredTrace(trace));
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                    queueLength.decrementAndGet();
                }
            });
        }
    }

    public void onStuckTrace(Trace trace) {
        try {
            traceDao.storeTrace(buildStoredTrace(trace));
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public int getQueueLength() {
        return queueLength.get();
    }

    private StoredTrace buildStoredTrace(Trace trace) throws IOException {
        long captureTick = ticker.read();
        StoredTrace.Builder builder = StoredTrace.builder();
        builder.id(trace.getId());
        builder.startAt(trace.getStartDate().getTime());
        builder.stuck(trace.isStuck() && !trace.isCompleted());
        builder.error(trace.isError());
        // timings for traces that are still active are normalized to the capture tick in order to
        // *attempt* to present a picture of the trace at that exact tick
        // (without using synchronization to block updates to the trace while it is being read)
        long endTick = trace.getEndTick();
        if (endTick != 0 && endTick <= captureTick) {
            builder.duration(trace.getDuration());
            builder.completed(true);
        } else {
            builder.duration(captureTick - trace.getStartTick());
            builder.completed(false);
        }
        Span rootSpan = trace.getRootSpan().getSpans().iterator().next();
        Message message = rootSpan.getMessageSupplier().get();
        builder.description(message.getText());
        builder.username(trace.getUsername().get());
        Collection<TraceAttribute> attributes = trace.getAttributes();
        if (!attributes.isEmpty()) {
            builder.attributes(gson.toJson(attributes));
        }
        builder.metrics(traceCommonJsonService.getMetricsJson(trace));
        builder.spans(traceCommonJsonService.getSpansByteStream(trace, captureTick));
        builder.mergedStackTree(TraceCommonJsonService.getMergedStackTree(trace));
        return builder.build();
    }

    public void close() {
        logger.debug("close()");
        executorService.shutdownNow();
    }
}
