/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.plugin.api;

/**
 * A {@link TraceEntry} that also captures query data for aggregation.
 */
public interface QueryEntry extends TraceEntry {

    /**
     * Example of query and subsequent iterating over results which goes back to database and pulls
     * more results.
     * 
     * Important note for async trace entries (those created by
     * {@link ThreadContext#startAsyncTraceEntry(MessageSupplier, TimerName)} and
     * {@link ThreadContext#startAsyncQueryEntry(String, String, QueryMessageSupplier, TimerName)}):
     * this method should not be used by a thread other than the one that created the async trace
     * entry.
     */
    Timer extend();

    void rowNavigationAttempted();

    /**
     * Call after successfully getting next row.
     */
    void incrementCurrRow();

    /**
     * Row numbers start at 1 (not 0).
     * 
     * @param row
     */
    void setCurrRow(long row);
}
