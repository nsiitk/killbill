/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.subscription.engine.dao;

import java.util.Date;
import java.util.List;

import org.skife.jdbi.v2.sqlobject.Bind;
import org.skife.jdbi.v2.sqlobject.BindBean;
import org.skife.jdbi.v2.sqlobject.SqlQuery;
import org.skife.jdbi.v2.sqlobject.SqlUpdate;

import com.ning.billing.subscription.engine.dao.model.SubscriptionEventModelDao;
import com.ning.billing.subscription.events.SubscriptionBaseEvent;
import com.ning.billing.util.audit.ChangeType;
import com.ning.billing.callcontext.InternalCallContext;
import com.ning.billing.callcontext.InternalTenantContext;
import com.ning.billing.util.entity.dao.Audited;
import com.ning.billing.util.entity.dao.EntitySqlDao;
import com.ning.billing.util.entity.dao.EntitySqlDaoStringTemplate;

@EntitySqlDaoStringTemplate
public interface SubscriptionEventSqlDao extends EntitySqlDao<SubscriptionEventModelDao, SubscriptionBaseEvent> {

    @SqlUpdate
    @Audited(ChangeType.UPDATE)
    public void unactiveEvent(@Bind("id") String id,
                              @BindBean final InternalCallContext context);

    @SqlUpdate
    @Audited(ChangeType.UPDATE)
    public void reactiveEvent(@Bind("id") String id,
                              @BindBean final InternalCallContext context);

    @SqlUpdate
    @Audited(ChangeType.UPDATE)
    public void updateVersion(@Bind("id") String id,
                              @Bind("currentVersion") Long currentVersion,
                              @BindBean final InternalCallContext context);

    @SqlQuery
    public List<SubscriptionEventModelDao> getFutureActiveEventForSubscription(@Bind("subscriptionId") String subscriptionId,
                                                                              @Bind("now") Date now,
                                                                              @BindBean final InternalTenantContext context);

    @SqlQuery
    public List<SubscriptionEventModelDao> getEventsForSubscription(@Bind("subscriptionId") String subscriptionId,
                                                                   @BindBean final InternalTenantContext context);
}
