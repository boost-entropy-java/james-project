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

package org.apache.james.backends.postgres.utils;

import org.apache.james.core.Domain;

import io.r2dbc.spi.Connection;
import reactor.core.publisher.Mono;

public interface JamesPostgresConnectionFactory {
    String DOMAIN_ATTRIBUTE = "app.current_domain";
    String BY_PASS_RLS_INJECT = "by_pass_rls";

    Mono<Connection> getConnection(Domain domain);

    Mono<Connection> getConnection();

    Mono<Void> closeConnection(Connection connection);

    Mono<Void> close();
}
