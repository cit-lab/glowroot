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
package org.informantproject.local.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.annotation.Nullable;

import org.informantproject.core.util.ByteStream;
import org.informantproject.local.trace.TraceCommonJsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read trace data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class TraceSummaryJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(TraceSummaryJsonService.class);

    private final TraceCommonJsonService traceCommonJsonService;

    @Inject
    TraceSummaryJsonService(TraceCommonJsonService traceCommonJsonService) {
        this.traceCommonJsonService = traceCommonJsonService;
    }

    // this method returns byte[] directly to avoid converting to it utf8 string and back again
    @JsonServiceMethod
    @Nullable
    byte[] getSummary(String id) throws IOException {
        logger.debug("getSummary(): id={}", id);
        ByteStream byteStreams = traceCommonJsonService.getStoredOrActiveTraceJson(id, false);
        if (byteStreams == null) {
            logger.error("no trace found for id '{}'", id);
            // TODO 404
            return null;
        } else {
            // summary is small and doesn't need to be streamed
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byteStreams.writeTo(baos);
            return baos.toByteArray();
        }
    }
}
