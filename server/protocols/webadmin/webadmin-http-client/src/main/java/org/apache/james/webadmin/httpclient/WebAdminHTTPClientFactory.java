/******************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one     *
 * or more contributor license agreements.  See the NOTICE file   *
 * distributed with this work for additional information          *
 * regarding copyright ownership.  The ASF licenses this file     *
 * to you under the Apache License, Version 2.0 (the              *
 * "License"); you may not use this file except in compliance     *
 * with the License.  You may obtain a copy of the License at     *
 *                                                                *
 * http://www.apache.org/licenses/LICENSE-2.0                     *
 *                                                                *
 * Unless required by applicable law or agreed to in writing,     *
 * software distributed under the License is distributed on an    *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY         *
 * KIND, either express or implied.  See the License for the      *
 * specific language governing permissions and limitations        *
 * under the License.                                             *
 ******************************************************************/

package org.apache.james.webadmin.httpclient;

import feign.Feign;
import feign.Logger;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import feign.okhttp.OkHttpClient;
import feign.slf4j.Slf4jLogger;

public class WebAdminHTTPClientFactory {

    public static Feign.Builder feignBuilder(String bearerToken) {
        return feignBuilder()
            .requestInterceptor(requestTemplate -> requestTemplate.header("Authorization", "Bearer " + bearerToken));
    }

    public static Feign.Builder feignBuilder() {
        return Feign.builder()
            .client(new OkHttpClient())
            .decoder(new JacksonDecoder())
            .encoder(new JacksonEncoder())
            .logger(new Slf4jLogger("james-webadmin-http-client"))
            .logLevel(Logger.Level.BASIC);
    }
}
