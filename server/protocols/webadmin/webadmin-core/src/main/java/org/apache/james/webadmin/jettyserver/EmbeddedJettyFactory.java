/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.webadmin.jettyserver;

import org.eclipse.jetty.util.thread.ThreadPool;

import spark.ExceptionMapper;
import spark.embeddedserver.EmbeddedServer;
import spark.embeddedserver.EmbeddedServerFactory;
import spark.embeddedserver.jetty.JettyServerFactory;
import spark.http.matching.MatcherFilter;
import spark.route.Routes;
import spark.staticfiles.StaticFilesConfiguration;

public class EmbeddedJettyFactory implements EmbeddedServerFactory {
    private final JettyServerFactory serverFactory;
    private ThreadPool threadPool;
    private boolean httpOnly = true;

    public EmbeddedJettyFactory() {
        this(new JettyServer());
    }

    public EmbeddedJettyFactory(JettyServerFactory serverFactory) {
        this.serverFactory = serverFactory;
    }

    public EmbeddedServer create(Routes routeMatcher,
                                 StaticFilesConfiguration staticFilesConfiguration,
                                 ExceptionMapper exceptionMapper,
                                 boolean hasMultipleHandler) {
        MatcherFilter matcherFilter = new MatcherFilter(routeMatcher, staticFilesConfiguration, exceptionMapper, false, hasMultipleHandler);
        matcherFilter.init(null);

        return new EmbeddedJettyServer(serverFactory, httpOnly, matcherFilter).withThreadPool(threadPool);
    }

    /**
     * Sets optional thread pool for jetty server.  This is useful for overriding the default thread pool
     * behaviour for example io.dropwizard.metrics.jetty9.InstrumentedQueuedThreadPool.
     *
     * @param threadPool thread pool
     * @return Builder pattern - returns this instance
     */
    public EmbeddedJettyFactory withThreadPool(ThreadPool threadPool) {
        this.threadPool = threadPool;
        return this;
    }

    public EmbeddedJettyFactory withHttpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
        return this;
    }
}
