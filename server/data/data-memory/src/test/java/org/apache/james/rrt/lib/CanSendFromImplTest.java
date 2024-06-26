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
package org.apache.james.rrt.lib;

import static org.mockito.Mockito.mock;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.james.UserEntityValidator;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.dnsservice.api.DNSService;
import org.apache.james.domainlist.lib.DomainListConfiguration;
import org.apache.james.domainlist.memory.MemoryDomainList;
import org.apache.james.rrt.api.AliasReverseResolver;
import org.apache.james.rrt.api.CanSendFrom;
import org.apache.james.rrt.api.RecipientRewriteTableConfiguration;
import org.apache.james.rrt.memory.MemoryRecipientRewriteTable;
import org.apache.james.user.memory.MemoryUsersRepository;
import org.junit.jupiter.api.BeforeEach;

public class CanSendFromImplTest implements CanSendFromContract {

    AbstractRecipientRewriteTable recipientRewriteTable;
    CanSendFrom canSendFrom;

    @BeforeEach
    void setup() throws Exception {
        recipientRewriteTable = new MemoryRecipientRewriteTable();

        DNSService dnsService = mock(DNSService.class);
        MemoryDomainList domainList = new MemoryDomainList(dnsService);
        domainList.configure(DomainListConfiguration.DEFAULT);
        domainList.addDomain(DOMAIN);
        domainList.addDomain(OTHER_DOMAIN);
        recipientRewriteTable.setDomainList(domainList);
        recipientRewriteTable.setConfiguration(RecipientRewriteTableConfiguration.DEFAULT_ENABLED);
        recipientRewriteTable.setUsersRepository(MemoryUsersRepository.withVirtualHosting(domainList));
        recipientRewriteTable.setUserEntityValidator(UserEntityValidator.NOOP);

        AliasReverseResolver aliasReverseResolver = new AliasReverseResolverImpl(recipientRewriteTable);
        canSendFrom = new CanSendFromImpl(aliasReverseResolver);
    }

    @Override
    public CanSendFrom canSendFrom() {
        return canSendFrom;
    }

    @Override
    public void addAliasMapping(Username alias, Username user) throws Exception {
        recipientRewriteTable.addAliasMapping(MappingSource.fromUser(alias.getLocalPart(), alias.getDomainPart().get()), user.asString());
    }

    @Override
    public void addDomainMapping(Domain alias, Domain domain, Mapping.Type mappingType) throws Exception {
        switch (mappingType) {
            case Domain:
                recipientRewriteTable.addDomainMapping(MappingSource.fromDomain(alias), domain);
                break;
            case DomainAlias:
                recipientRewriteTable.addDomainAliasMapping(MappingSource.fromDomain(alias), domain);
                break;
            default:
                throw new NotImplementedException(mappingType + " is not supported");
        }
    }

    @Override
    public void addGroupMapping(String group, Username user) throws Exception {
        recipientRewriteTable.addGroupMapping(MappingSource.fromUser(Username.of(group)), user.asString());
    }
}
