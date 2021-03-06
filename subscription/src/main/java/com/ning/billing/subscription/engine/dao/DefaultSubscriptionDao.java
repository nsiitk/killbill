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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.joda.time.DateTime;
import org.skife.jdbi.v2.IDBI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.billing.ErrorCode;
import com.ning.billing.bus.api.PersistentBus;
import com.ning.billing.bus.api.PersistentBus.EventBusException;
import com.ning.billing.catalog.api.CatalogService;
import com.ning.billing.catalog.api.Plan;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.clock.Clock;
import com.ning.billing.subscription.api.migration.AccountMigrationData;
import com.ning.billing.subscription.api.migration.AccountMigrationData.BundleMigrationData;
import com.ning.billing.subscription.api.migration.AccountMigrationData.SubscriptionMigrationData;
import com.ning.billing.subscription.api.timeline.DefaultRepairSubscriptionEvent;
import com.ning.billing.subscription.api.timeline.SubscriptionDataRepair;
import com.ning.billing.subscription.api.transfer.TransferCancelData;
import com.ning.billing.subscription.api.user.DefaultEffectiveSubscriptionEvent;
import com.ning.billing.subscription.api.user.DefaultRequestedSubscriptionEvent;
import com.ning.billing.subscription.api.user.DefaultSubscriptionBase;
import com.ning.billing.subscription.api.user.DefaultSubscriptionBaseBundle;
import com.ning.billing.subscription.api.user.SubscriptionBaseBundle;
import com.ning.billing.subscription.api.user.SubscriptionBuilder;
import com.ning.billing.subscription.api.user.SubscriptionBaseTransitionData;
import com.ning.billing.subscription.engine.addon.AddonUtils;
import com.ning.billing.subscription.engine.core.DefaultSubscriptionBaseService;
import com.ning.billing.subscription.engine.core.SubscriptionNotificationKey;
import com.ning.billing.subscription.engine.dao.model.SubscriptionEventModelDao;
import com.ning.billing.subscription.engine.dao.model.SubscriptionBundleModelDao;
import com.ning.billing.subscription.engine.dao.model.SubscriptionModelDao;
import com.ning.billing.subscription.events.SubscriptionBaseEvent;
import com.ning.billing.subscription.events.SubscriptionBaseEvent.EventType;
import com.ning.billing.subscription.events.phase.PhaseEvent;
import com.ning.billing.subscription.events.user.ApiEvent;
import com.ning.billing.subscription.events.user.ApiEventBuilder;
import com.ning.billing.subscription.events.user.ApiEventCancel;
import com.ning.billing.subscription.events.user.ApiEventChange;
import com.ning.billing.subscription.events.user.ApiEventMigrateBilling;
import com.ning.billing.subscription.events.user.ApiEventType;
import com.ning.billing.subscription.exceptions.SubscriptionBaseError;
import com.ning.billing.notificationq.api.NotificationEvent;
import com.ning.billing.notificationq.api.NotificationQueue;
import com.ning.billing.notificationq.api.NotificationQueueService;
import com.ning.billing.notificationq.api.NotificationQueueService.NoSuchNotificationQueue;
import com.ning.billing.subscription.api.SubscriptionBase;
import com.ning.billing.util.cache.CacheControllerDispatcher;
import com.ning.billing.callcontext.InternalCallContext;
import com.ning.billing.callcontext.InternalTenantContext;
import com.ning.billing.util.dao.NonEntityDao;
import com.ning.billing.entity.EntityPersistenceException;
import com.ning.billing.util.entity.dao.EntitySqlDao;
import com.ning.billing.util.entity.dao.EntitySqlDaoTransactionWrapper;
import com.ning.billing.util.entity.dao.EntitySqlDaoTransactionalJdbiWrapper;
import com.ning.billing.util.entity.dao.EntitySqlDaoWrapperFactory;
import com.ning.billing.events.EffectiveSubscriptionInternalEvent;
import com.ning.billing.events.RepairSubscriptionInternalEvent;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;


public class DefaultSubscriptionDao implements SubscriptionDao {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubscriptionDao.class);

    private final Clock clock;
    private final EntitySqlDaoTransactionalJdbiWrapper transactionalSqlDao;
    private final NotificationQueueService notificationQueueService;
    private final AddonUtils addonUtils;
    private final PersistentBus eventBus;
    private final CatalogService catalogService;

    @Inject
    public DefaultSubscriptionDao(final IDBI dbi, final Clock clock, final AddonUtils addonUtils,
                                  final NotificationQueueService notificationQueueService, final PersistentBus eventBus, final CatalogService catalogService,
                                  final CacheControllerDispatcher cacheControllerDispatcher, final NonEntityDao nonEntityDao) {
        this.clock = clock;
        this.transactionalSqlDao = new EntitySqlDaoTransactionalJdbiWrapper(dbi, clock, cacheControllerDispatcher, nonEntityDao);
        this.notificationQueueService = notificationQueueService;
        this.addonUtils = addonUtils;
        this.eventBus = eventBus;
        this.catalogService = catalogService;
    }

    @Override
    public List<SubscriptionBaseBundle> getSubscriptionBundlesForAccountAndKey(final UUID accountId, final String bundleKey, final InternalTenantContext context) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<List<SubscriptionBaseBundle>>() {
            @Override
            public List<SubscriptionBaseBundle> inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final List<SubscriptionBundleModelDao> models = entitySqlDaoWrapperFactory.become(BundleSqlDao.class).getBundlesFromAccountAndKey(accountId.toString(), bundleKey, context);
                return new ArrayList<SubscriptionBaseBundle>(Collections2.transform(models, new Function<SubscriptionBundleModelDao, SubscriptionBaseBundle>() {
                    @Override
                    public SubscriptionBaseBundle apply(@Nullable final SubscriptionBundleModelDao input) {
                        return SubscriptionBundleModelDao.toSubscriptionbundle(input);
                    }
                }));
            }
        });
    }

    @Override
    public List<SubscriptionBaseBundle> getSubscriptionBundleForAccount(final UUID accountId, final InternalTenantContext context) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<List<SubscriptionBaseBundle>>() {
            @Override
            public List<SubscriptionBaseBundle> inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final List<SubscriptionBundleModelDao> models = entitySqlDaoWrapperFactory.become(BundleSqlDao.class).getBundleFromAccount(accountId.toString(), context);

                return new ArrayList<SubscriptionBaseBundle>(Collections2.transform(models, new Function<SubscriptionBundleModelDao, SubscriptionBaseBundle>() {
                    @Override
                    public SubscriptionBaseBundle apply(@Nullable final SubscriptionBundleModelDao input) {
                        return SubscriptionBundleModelDao.toSubscriptionbundle(input);
                    }
                }));
            }
        });
    }

    @Override
    public SubscriptionBaseBundle getSubscriptionBundleFromId(final UUID bundleId, final InternalTenantContext context) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<SubscriptionBaseBundle>() {
            @Override
            public SubscriptionBaseBundle inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final SubscriptionBundleModelDao model = entitySqlDaoWrapperFactory.become(BundleSqlDao.class).getById(bundleId.toString(), context);
                return SubscriptionBundleModelDao.toSubscriptionbundle(model);
            }
        });
    }

    @Override
    public List<SubscriptionBaseBundle> getSubscriptionBundlesForKey(final String bundleKey, final InternalTenantContext context) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<List<SubscriptionBaseBundle>>() {
            @Override
            public List<SubscriptionBaseBundle> inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final List<SubscriptionBundleModelDao> models = entitySqlDaoWrapperFactory.become(BundleSqlDao.class).getBundlesForKey(bundleKey, context);
                return new ArrayList<SubscriptionBaseBundle>(Collections2.transform(models, new Function<SubscriptionBundleModelDao, SubscriptionBaseBundle>() {
                    @Override
                    public SubscriptionBaseBundle apply(@Nullable final SubscriptionBundleModelDao input) {
                        return SubscriptionBundleModelDao.toSubscriptionbundle(input);
                    }
                }));
            }
        });
    }

    @Override
    public SubscriptionBaseBundle createSubscriptionBundle(final DefaultSubscriptionBaseBundle bundle, final InternalCallContext context) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<SubscriptionBaseBundle>() {
            @Override
            public SubscriptionBaseBundle inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws EntityPersistenceException {

                final SubscriptionBundleModelDao model = new SubscriptionBundleModelDao(bundle);
                entitySqlDaoWrapperFactory.become(BundleSqlDao.class).create(model, context);
                final SubscriptionBundleModelDao result = entitySqlDaoWrapperFactory.become(BundleSqlDao.class).getById(bundle.getId().toString(), context);
                return SubscriptionBundleModelDao.toSubscriptionbundle(result);
            }
        });
    }

    @Override
    public UUID getAccountIdFromSubscriptionId(final UUID subscriptionId, final InternalTenantContext context) {

        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<UUID>() {
            @Override
            public UUID inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final SubscriptionModelDao subscriptionModel = entitySqlDaoWrapperFactory.become(SubscriptionSqlDao.class).getById(subscriptionId.toString(), context);
                if (subscriptionModel == null) {
                    log.error(String.format(ErrorCode.SUB_INVALID_SUBSCRIPTION_ID.getFormat(), subscriptionId.toString()));
                    return null;
                }

                final UUID bundleId = subscriptionModel.getBundleId();
                if (bundleId == null) {
                    log.error(String.format(ErrorCode.SUB_GET_NO_BUNDLE_FOR_SUBSCRIPTION.getFormat(), subscriptionId.toString()));
                    return null;
                }

                final SubscriptionBundleModelDao bundleModel = entitySqlDaoWrapperFactory.become(BundleSqlDao.class).getById(bundleId.toString(), context);
                if (bundleModel == null) {
                    log.error(String.format(ErrorCode.SUB_GET_INVALID_BUNDLE_ID.getFormat(), bundleId.toString()));
                    return null;
                }
                return bundleModel.getAccountId();
            }
        });
    }

    @Override
    public SubscriptionBase getBaseSubscription(final UUID bundleId, final InternalTenantContext context) {
        return getBaseSubscription(bundleId, true, context);
    }

    @Override
    public SubscriptionBase getSubscriptionFromId(final UUID subscriptionId, final InternalTenantContext context) {
        final SubscriptionBase shellSubscription = transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<SubscriptionBase>() {
            @Override
            public SubscriptionBase inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final SubscriptionModelDao model = entitySqlDaoWrapperFactory.become(SubscriptionSqlDao.class).getById(subscriptionId.toString(), context);
                return SubscriptionModelDao.toSubscription(model);
            }
        });
        return buildSubscription(shellSubscription, context);
    }


    @Override
    public List<SubscriptionBase> getSubscriptions(final UUID bundleId, final InternalTenantContext context) {
        return buildBundleSubscriptions(bundleId, getSubscriptionFromBundleId(bundleId, context), context);
    }

    private List<SubscriptionBase> getSubscriptionFromBundleId(final UUID bundleId, final InternalTenantContext context) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<List<SubscriptionBase>>() {
            @Override
            public List<SubscriptionBase> inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final List<SubscriptionModelDao> models = entitySqlDaoWrapperFactory.become(SubscriptionSqlDao.class).getSubscriptionsFromBundleId(bundleId.toString(), context);
                return new ArrayList<SubscriptionBase>(Collections2.transform(models, new Function<SubscriptionModelDao, SubscriptionBase>() {
                    @Override
                    public SubscriptionBase apply(@Nullable final SubscriptionModelDao input) {
                        return SubscriptionModelDao.toSubscription(input);
                    }
                }));
            }
        });
    }

    @Override
    public Map<UUID, List<SubscriptionBase>> getSubscriptionsForAccount(final InternalTenantContext context) {
        final Map<UUID, List<SubscriptionBase>> subscriptionsFromAccountId = getSubscriptionsFromAccountId(context);

        final Map<UUID, List<SubscriptionBase>> result = new HashMap<UUID, List<SubscriptionBase>>();
        for (final UUID bundleId : subscriptionsFromAccountId.keySet()) {
            result.put(bundleId, buildBundleSubscriptions(bundleId, subscriptionsFromAccountId.get(bundleId), context));
        }
        return result;
    }

    private Map<UUID, List<SubscriptionBase>> getSubscriptionsFromAccountId(final InternalTenantContext context) {
        final List<SubscriptionBase> allSubscriptions = transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<List<SubscriptionBase>>() {
            @Override
            public List<SubscriptionBase> inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final List<SubscriptionModelDao> models = entitySqlDaoWrapperFactory.become(SubscriptionSqlDao.class).getByAccountRecordId(context);
                return new ArrayList<SubscriptionBase>(Collections2.transform(models, new Function<SubscriptionModelDao, SubscriptionBase>() {
                    @Override
                    public SubscriptionBase apply(final SubscriptionModelDao input) {
                        return SubscriptionModelDao.toSubscription(input);
                    }
                }));
            }
        });

        final Map<UUID, List<SubscriptionBase>> result = new HashMap<UUID, List<SubscriptionBase>>();
        for (final SubscriptionBase subscriptionBase : allSubscriptions) {
            if (result.get(subscriptionBase.getBundleId()) == null) {
                result.put(subscriptionBase.getBundleId(), new LinkedList<SubscriptionBase>());
            }
            result.get(subscriptionBase.getBundleId()).add(subscriptionBase);
        }
        return result;
    }

    /*
    @Override
    public List<SubscriptionBase> getSubscriptionsForAccountAndKey(final UUID accountId,
                                                               final String bundleKey, final InternalTenantContext callcontext) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<List<SubscriptionBase>>() {
            @Override
            public List<SubscriptionBase> inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final SubscriptionBundleModelDao bundleModel = entitySqlDaoWrapperFactory.become(BundleSqlDao.class).getBundlesFromAccountAndKey(accountId.toString(), bundleKey, callcontext);
                if (bundleModel == null) {
                    return Collections.emptyList();
                }
                return getSubscriptions(bundleModel.getId(), callcontext);
            }
        });
    }
    */

    @Override
    public void updateChargedThroughDate(final DefaultSubscriptionBase subscription, final InternalCallContext context) {
        final Date ctd = (subscription.getChargedThroughDate() != null) ? subscription.getChargedThroughDate().toDate() : null;

        transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Void>() {
            @Override
            public Void inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final SubscriptionSqlDao transactionalDao = entitySqlDaoWrapperFactory.become(SubscriptionSqlDao.class);
                transactionalDao.updateChargedThroughDate(subscription.getId().toString(), ctd, context);

                final BundleSqlDao bundleSqlDao = entitySqlDaoWrapperFactory.become(BundleSqlDao.class);
                final String bundleId = subscription.getBundleId().toString();
                bundleSqlDao.updateBundleLastSysTime(bundleId, clock.getUTCNow().toDate(), context);
                return null;
            }
        });
    }

    @Override
    public void createNextPhaseEvent(final DefaultSubscriptionBase subscription, final SubscriptionBaseEvent nextPhase, final InternalCallContext context) {
        transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Void>() {
            @Override
            public Void inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final SubscriptionEventSqlDao transactional = entitySqlDaoWrapperFactory.become(SubscriptionEventSqlDao.class);
                final UUID subscriptionId = subscription.getId();
                cancelNextPhaseEventFromTransaction(subscriptionId, entitySqlDaoWrapperFactory, context);
                transactional.create(new SubscriptionEventModelDao(nextPhase), context);
                recordFutureNotificationFromTransaction(entitySqlDaoWrapperFactory,
                                                        nextPhase.getEffectiveDate(),
                                                        new SubscriptionNotificationKey(nextPhase.getId()), context);

                // Notify the Bus of the requested change
                notifyBusOfRequestedChange(entitySqlDaoWrapperFactory, subscription, nextPhase, context);

                return null;
            }
        });
    }

    @Override
    public SubscriptionBaseEvent getEventById(final UUID eventId, final InternalTenantContext context) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<SubscriptionBaseEvent>() {
            @Override
            public SubscriptionBaseEvent inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                SubscriptionEventModelDao model = entitySqlDaoWrapperFactory.become(SubscriptionEventSqlDao.class).getById(eventId.toString(), context);
                return SubscriptionEventModelDao.toSubscriptionEvent(model);
            }
        });
    }

    @Override
    public List<SubscriptionBaseEvent> getEventsForSubscription(final UUID subscriptionId, final InternalTenantContext context) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<List<SubscriptionBaseEvent>>() {
            @Override
            public List<SubscriptionBaseEvent> inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final List<SubscriptionEventModelDao> models = entitySqlDaoWrapperFactory.become(SubscriptionEventSqlDao.class).getEventsForSubscription(subscriptionId.toString(), context);
                // Remove UNCANCEL events early on as they are not representative of a state transition but are just markers
                final Collection<SubscriptionEventModelDao> filteredModels = Collections2.filter(models, new Predicate<SubscriptionEventModelDao>() {
                    @Override
                    public boolean apply(@Nullable final SubscriptionEventModelDao input) {
                        return input.getUserType() != ApiEventType.UNCANCEL;
                    }
                });
                return new ArrayList<SubscriptionBaseEvent>(Collections2.transform(filteredModels, new Function<SubscriptionEventModelDao, SubscriptionBaseEvent>() {
                    @Override
                    public SubscriptionBaseEvent apply(@Nullable final SubscriptionEventModelDao input) {
                        return SubscriptionEventModelDao.toSubscriptionEvent(input);
                    }
                }));
            }
        });
    }

    @Override
    public Map<UUID, List<SubscriptionBaseEvent>> getEventsForBundle(final UUID bundleId, final InternalTenantContext context) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Map<UUID, List<SubscriptionBaseEvent>>>() {
            @Override
            public Map<UUID, List<SubscriptionBaseEvent>> inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final SubscriptionSqlDao transactional = entitySqlDaoWrapperFactory.become(SubscriptionSqlDao.class);

                final List<SubscriptionModelDao> subscriptionModels = transactional.getSubscriptionsFromBundleId(bundleId.toString(), context);
                if (subscriptionModels.size() == 0) {
                    return Collections.emptyMap();
                }

                final SubscriptionEventSqlDao eventsDaoFromSameTransaction = entitySqlDaoWrapperFactory.become(SubscriptionEventSqlDao.class);
                final Map<UUID, List<SubscriptionBaseEvent>> result = new HashMap<UUID, List<SubscriptionBaseEvent>>();
                for (final SubscriptionModelDao cur : subscriptionModels) {
                    final List<SubscriptionEventModelDao> eventModels = eventsDaoFromSameTransaction.getEventsForSubscription(cur.getId().toString(), context);
                    final List<SubscriptionBaseEvent> events = new ArrayList<SubscriptionBaseEvent>(Collections2.transform(eventModels, new Function<SubscriptionEventModelDao, SubscriptionBaseEvent>() {
                        @Override
                        public SubscriptionBaseEvent apply(@Nullable final SubscriptionEventModelDao input) {
                            return SubscriptionEventModelDao.toSubscriptionEvent(input);
                        }
                    }));
                    result.put(cur.getId(), events);
                }
                return result;
            }
        });
    }

    @Override
    public List<SubscriptionBaseEvent> getPendingEventsForSubscription(final UUID subscriptionId, final InternalTenantContext context) {
        final Date now = clock.getUTCNow().toDate();

        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<List<SubscriptionBaseEvent>>() {
            @Override
            public List<SubscriptionBaseEvent> inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final List<SubscriptionEventModelDao> eventModels = entitySqlDaoWrapperFactory.become(SubscriptionEventSqlDao.class).getFutureActiveEventForSubscription(subscriptionId.toString(), now, context);
                final List<SubscriptionBaseEvent> events = new ArrayList<SubscriptionBaseEvent>(Collections2.transform(eventModels, new Function<SubscriptionEventModelDao, SubscriptionBaseEvent>() {
                    @Override
                    public SubscriptionBaseEvent apply(@Nullable final SubscriptionEventModelDao input) {
                        return SubscriptionEventModelDao.toSubscriptionEvent(input);
                    }
                }));
                return events;
            }
        });
    }

    @Override
    public void createSubscription(final DefaultSubscriptionBase subscription, final List<SubscriptionBaseEvent> initialEvents, final InternalCallContext context) {
        transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Void>() {
            @Override
            public Void inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final SubscriptionSqlDao transactional = entitySqlDaoWrapperFactory.become(SubscriptionSqlDao.class);
                transactional.create(new SubscriptionModelDao(subscription), context);

                final SubscriptionEventSqlDao eventsDaoFromSameTransaction = entitySqlDaoWrapperFactory.become(SubscriptionEventSqlDao.class);
                for (final SubscriptionBaseEvent cur : initialEvents) {
                    eventsDaoFromSameTransaction.create(new SubscriptionEventModelDao(cur), context);

                    final boolean isBusEvent = cur.getEffectiveDate().compareTo(clock.getUTCNow()) <= 0 && (cur.getType() == EventType.API_USER);
                    recordBusOrFutureNotificationFromTransaction(subscription, cur, entitySqlDaoWrapperFactory, isBusEvent, 0, context);

                }
                // Notify the Bus of the latest requested change, if needed
                if (initialEvents.size() > 0) {
                    notifyBusOfRequestedChange(entitySqlDaoWrapperFactory, subscription, initialEvents.get(initialEvents.size() - 1), context);
                }
                return null;
            }
        });
    }


    @Override
    public void recreateSubscription(final DefaultSubscriptionBase subscription, final List<SubscriptionBaseEvent> recreateEvents, final InternalCallContext context) {
        transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Void>() {
            @Override
            public Void inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final SubscriptionEventSqlDao transactional = entitySqlDaoWrapperFactory.become(SubscriptionEventSqlDao.class);

                for (final SubscriptionBaseEvent cur : recreateEvents) {
                    transactional.create(new SubscriptionEventModelDao(cur), context);

                    final boolean isBusEvent = cur.getEffectiveDate().compareTo(clock.getUTCNow()) <= 0 && (cur.getType() == EventType.API_USER);
                    recordBusOrFutureNotificationFromTransaction(subscription, cur, entitySqlDaoWrapperFactory, isBusEvent, 0, context);
                }

                // Notify the Bus of the latest requested change
                notifyBusOfRequestedChange(entitySqlDaoWrapperFactory, subscription, recreateEvents.get(recreateEvents.size() - 1), context);

                return null;
            }
        });
    }

    @Override
    public void cancelSubscriptions(final List<DefaultSubscriptionBase> subscriptions, final List<SubscriptionBaseEvent> cancelEvents, final InternalCallContext context) {

        transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Void>() {
            @Override
            public Void inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                for (int i = 0; i < subscriptions.size(); i++) {
                    final DefaultSubscriptionBase subscription = subscriptions.get(i);
                    final SubscriptionBaseEvent cancelEvent = cancelEvents.get(i);
                    cancelSubscriptionFromTransaction(subscription, cancelEvent, entitySqlDaoWrapperFactory, context, i);
                }
                return null;
            }
        });
    }


    @Override
    public void cancelSubscription(final DefaultSubscriptionBase subscription, final SubscriptionBaseEvent cancelEvent, final InternalCallContext context, final int seqId) {
        transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Void>() {
            @Override
            public Void inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                cancelSubscriptionFromTransaction(subscription, cancelEvent, entitySqlDaoWrapperFactory, context, seqId);
                return null;
            }
        });
    }

    @Override
    public void uncancelSubscription(final DefaultSubscriptionBase subscription, final List<SubscriptionBaseEvent> uncancelEvents, final InternalCallContext context) {
        transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Void>() {
            @Override
            public Void inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final SubscriptionEventSqlDao transactional = entitySqlDaoWrapperFactory.become(SubscriptionEventSqlDao.class);

                final UUID subscriptionId = subscription.getId();
                SubscriptionEventModelDao cancelledEvent = null;
                final Date now = clock.getUTCNow().toDate();
                final List<SubscriptionEventModelDao> eventModels = transactional.getFutureActiveEventForSubscription(subscriptionId.toString(), now, context);

                for (final SubscriptionEventModelDao cur : eventModels) {
                    if (cur.getUserType() == ApiEventType.CANCEL) {
                        if (cancelledEvent != null) {
                            throw new SubscriptionBaseError(String.format("Found multiple cancelWithRequestedDate active events for subscriptions %s", subscriptionId.toString()));
                        }
                        cancelledEvent = cur;
                    }
                }

                if (cancelledEvent != null) {
                    final String cancelledEventId = cancelledEvent.getId().toString();
                    transactional.unactiveEvent(cancelledEventId, context);
                    for (final SubscriptionBaseEvent cur : uncancelEvents) {
                        transactional.create(new SubscriptionEventModelDao(cur), context);
                        recordFutureNotificationFromTransaction(entitySqlDaoWrapperFactory,
                                                                cur.getEffectiveDate(),
                                                                new SubscriptionNotificationKey(cur.getId()),
                                                                context);
                    }

                    // Notify the Bus of the latest requested change
                    notifyBusOfRequestedChange(entitySqlDaoWrapperFactory, subscription, uncancelEvents.get(uncancelEvents.size() - 1), context);
                }

                return null;
            }
        });
    }

    @Override
    public void changePlan(final DefaultSubscriptionBase subscription, final List<SubscriptionBaseEvent> changeEvents, final InternalCallContext context) {
        transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Void>() {
            @Override
            public Void inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {

                final SubscriptionEventSqlDao transactional = entitySqlDaoWrapperFactory.become(SubscriptionEventSqlDao.class);
                final UUID subscriptionId = subscription.getId();

                final List<SubscriptionBaseEvent> changeEventsTweakedWithMigrateBilling = reinsertFutureMigrateBillingEventOnChangeFromTransaction(subscriptionId,
                                                                                                                                              changeEvents,
                                                                                                                                              entitySqlDaoWrapperFactory,
                                                                                                                                              context);

                cancelFutureEventsFromTransaction(subscriptionId, entitySqlDaoWrapperFactory, context);

                for (final SubscriptionBaseEvent cur : changeEventsTweakedWithMigrateBilling) {

                    transactional.create(new SubscriptionEventModelDao(cur), context);

                    final boolean isBusEvent = cur.getEffectiveDate().compareTo(clock.getUTCNow()) <= 0 && (cur.getType() == EventType.API_USER);
                    recordBusOrFutureNotificationFromTransaction(subscription, cur, entitySqlDaoWrapperFactory, isBusEvent, 0, context);
                }

                // Notify the Bus of the latest requested change
                final SubscriptionBaseEvent finalEvent = changeEventsTweakedWithMigrateBilling.get(changeEvents.size() - 1);
                notifyBusOfRequestedChange(entitySqlDaoWrapperFactory, subscription, finalEvent, context);

                return null;
            }
        });
    }

    //
    // This piece of code has been isolated in its own method in order to allow for migrated subscriptions to have their plan to changed prior
    // to MIGRATE_BILLING; the effect will be to reflect the change from an subscription point of view while ignoring the change until we hit
    // the begining of the billing, that is when we hit the MIGRATE_BILLING event. If we had a clear separation between subscription and
    // billing that would not be needed.
    //
    // If there is a change of plan prior to a future MIGRATE_BILLING, we want to modify the existing MIGRATE_BILLING so it reflects
    // the new plan, phase, pricelist; Invoice will only see the MIGRATE_BILLING as things prior to that will be ignored, so we need to make sure
    // that event reflects the correct subscription information.
    //
    //
    final List<SubscriptionBaseEvent> reinsertFutureMigrateBillingEventOnChangeFromTransaction(final UUID subscriptionId, final List<SubscriptionBaseEvent> changeEvents, final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory, final InternalCallContext context) {
        final SubscriptionEventModelDao migrateBillingEvent = findFutureEventFromTransaction(subscriptionId, entitySqlDaoWrapperFactory, EventType.API_USER, ApiEventType.MIGRATE_BILLING, context);
        if (migrateBillingEvent == null) {
            // No future migrate billing : returns same list
            return changeEvents;
        }

        String prevPlan = null;
        String prevPhase = null;
        String prevPriceList = null;
        String curPlan = null;
        String curPhase = null;
        String curPriceList = null;
        for (SubscriptionBaseEvent cur : changeEvents) {
            switch (cur.getType()) {
                case API_USER:
                    final ApiEvent apiEvent = (ApiEvent) cur;
                    curPlan = apiEvent.getEventPlan();
                    curPhase = apiEvent.getEventPlanPhase();
                    curPriceList = apiEvent.getPriceList();
                    break;

                case PHASE:
                    final PhaseEvent phaseEvent = (PhaseEvent) cur;
                    curPhase = phaseEvent.getPhase();
                    break;

                default:
                    throw new SubscriptionBaseError("Unknown event type " + cur.getType());
            }

            if (cur.getEffectiveDate().compareTo(migrateBillingEvent.getEffectiveDate()) > 0) {
                if (cur.getType() == EventType.API_USER && ((ApiEvent) cur).getEventType() == ApiEventType.CHANGE) {
                    // This is an EOT change that is occurring after the MigrateBilling : returns same list
                    return changeEvents;
                }
                // We found the first event after the migrate billing
                break;
            }
            prevPlan = curPlan;
            prevPhase = curPhase;
            prevPriceList = curPriceList;
        }

        if (prevPlan != null) {
            // Create the new MIGRATE_BILLING with same effectiveDate but new plan information
            final DateTime now = clock.getUTCNow();
            final ApiEventBuilder builder = new ApiEventBuilder()
                    .setActive(true)
                    .setEventType(ApiEventType.MIGRATE_BILLING)
                    .setFromDisk(true)
                    .setTotalOrdering(migrateBillingEvent.getTotalOrdering())
                    .setUuid(UUID.randomUUID())
                    .setSubscriptionId(migrateBillingEvent.getSubscriptionId())
                    .setCreatedDate(now)
                    .setUpdatedDate(now)
                    .setRequestedDate(migrateBillingEvent.getRequestedDate())
                    .setEffectiveDate(migrateBillingEvent.getEffectiveDate())
                    .setProcessedDate(now)
                    .setActiveVersion(migrateBillingEvent.getCurrentVersion())
                    .setEventPlan(prevPlan)
                    .setEventPlanPhase(prevPhase)
                    .setEventPriceList(prevPriceList);

            final SubscriptionBaseEvent newMigrateBillingEvent = new ApiEventMigrateBilling(builder);
            changeEvents.add(newMigrateBillingEvent);

            Collections.sort(changeEvents, new Comparator<SubscriptionBaseEvent>() {
                @Override
                public int compare(final SubscriptionBaseEvent o1, final SubscriptionBaseEvent o2) {
                    return o1.getEffectiveDate().compareTo(o2.getEffectiveDate());
                }
            });
        }

        return changeEvents;
    }

    private void cancelSubscriptionFromTransaction(final DefaultSubscriptionBase subscription, final SubscriptionBaseEvent cancelEvent, final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory, final InternalCallContext context, final int seqId)
            throws EntityPersistenceException {
        final UUID subscriptionId = subscription.getId();
        cancelFutureEventsFromTransaction(subscriptionId, entitySqlDaoWrapperFactory, context);
        entitySqlDaoWrapperFactory.become(SubscriptionEventSqlDao.class).create(new SubscriptionEventModelDao(cancelEvent), context);

        final boolean isBusEvent = cancelEvent.getEffectiveDate().compareTo(clock.getUTCNow()) <= 0;
        recordBusOrFutureNotificationFromTransaction(subscription, cancelEvent, entitySqlDaoWrapperFactory, isBusEvent, seqId, context);

        // Notify the Bus of the requested change
        notifyBusOfRequestedChange(entitySqlDaoWrapperFactory, subscription, cancelEvent, context);
    }

    private void cancelNextPhaseEventFromTransaction(final UUID subscriptionId, final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory, final InternalCallContext context) {
        cancelFutureEventFromTransaction(subscriptionId, entitySqlDaoWrapperFactory, EventType.PHASE, null, context);
    }

    private void cancelFutureEventsFromTransaction(final UUID subscriptionId, final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory, final InternalCallContext context) {
        final Date now = clock.getUTCNow().toDate();
        final List<SubscriptionEventModelDao> eventModels = entitySqlDaoWrapperFactory.become(SubscriptionEventSqlDao.class).getFutureActiveEventForSubscription(subscriptionId.toString(), now, context);
        for (final SubscriptionEventModelDao cur : eventModels) {
            unactivateEventFromTransaction(cur, entitySqlDaoWrapperFactory, context);
        }
    }

    private void cancelFutureEventFromTransaction(final UUID subscriptionId, final EntitySqlDaoWrapperFactory<EntitySqlDao> dao, final EventType type,
                                                  @Nullable final ApiEventType apiType, final InternalCallContext context) {
        final SubscriptionEventModelDao futureEvent = findFutureEventFromTransaction(subscriptionId, dao, type, apiType, context);
        unactivateEventFromTransaction(futureEvent, dao, context);
    }

    private SubscriptionEventModelDao findFutureEventFromTransaction(final UUID subscriptionId, final EntitySqlDaoWrapperFactory<EntitySqlDao> dao, final EventType type,
                                                                    @Nullable final ApiEventType apiType, final InternalCallContext context) {

        SubscriptionEventModelDao futureEvent = null;
        final Date now = clock.getUTCNow().toDate();
        final List<SubscriptionEventModelDao> eventModels = dao.become(SubscriptionEventSqlDao.class).getFutureActiveEventForSubscription(subscriptionId.toString(), now, context);
        for (final SubscriptionEventModelDao cur : eventModels) {
            if (cur.getEventType() == type &&
                (apiType == null || apiType == cur.getUserType())) {
                if (futureEvent != null) {
                    throw new SubscriptionBaseError(String.format("Found multiple future events for type %s for subscriptions %s",
                                                             type, subscriptionId.toString()));
                }
                futureEvent = cur;
                // To check that there is only one such event
                //break;
            }
        }
        return futureEvent;
    }

    private void unactivateEventFromTransaction(final SubscriptionEventModelDao event, final EntitySqlDaoWrapperFactory<EntitySqlDao> dao, final InternalCallContext context) {
        if (event != null) {
            final String eventId = event.getId().toString();
            dao.become(SubscriptionEventSqlDao.class).unactiveEvent(eventId, context);
        }
    }

    private SubscriptionBase buildSubscription(final SubscriptionBase input, final InternalTenantContext context) {

        if (input == null) {
            return null;
        }
        final List<SubscriptionBase> bundleInput = new ArrayList<SubscriptionBase>();
        if (input.getCategory() == ProductCategory.ADD_ON) {
            final SubscriptionBase baseSubscription = getBaseSubscription(input.getBundleId(), false, context);
            if (baseSubscription == null) {
                return null;
            }

            bundleInput.add(baseSubscription);
            bundleInput.add(input);
        } else {
            bundleInput.add(input);
        }

        final List<SubscriptionBase> reloadedSubscriptions = buildBundleSubscriptions(input.getBundleId(), bundleInput, context);
        for (final SubscriptionBase cur : reloadedSubscriptions) {
            if (cur.getId().equals(input.getId())) {
                return cur;
            }
        }

        throw new SubscriptionBaseError("Unexpected code path in buildSubscription");
    }

    private List<SubscriptionBase> buildBundleSubscriptions(final UUID bundleId, final List<SubscriptionBase> input, final InternalTenantContext context) {
        if (input == null || input.size() == 0) {
            return Collections.emptyList();
        }

        // Make sure BasePlan -- if exists-- is first
        Collections.sort(input, new Comparator<SubscriptionBase>() {
            @Override
            public int compare(final SubscriptionBase o1, final SubscriptionBase o2) {
                if (o1.getCategory() == ProductCategory.BASE) {
                    return -1;
                } else if (o2.getCategory() == ProductCategory.BASE) {
                    return 1;
                } else {
                    return ((DefaultSubscriptionBase) o1).getAlignStartDate().compareTo(((DefaultSubscriptionBase) o2).getAlignStartDate());
                }
            }
        });

        SubscriptionBaseEvent futureBaseEvent = null;
        final List<SubscriptionBase> result = new ArrayList<SubscriptionBase>(input.size());
        for (final SubscriptionBase cur : input) {
            final List<SubscriptionBaseEvent> events = getEventsForSubscription(cur.getId(), context);
            SubscriptionBase reloaded = createSubscriptionForInternalUse(cur, events);

            switch (cur.getCategory()) {
                case BASE:
                    final Collection<SubscriptionBaseEvent> futureApiEvents = Collections2.filter(events, new Predicate<SubscriptionBaseEvent>() {
                        @Override
                        public boolean apply(final SubscriptionBaseEvent input) {
                            return (input.isActive() && input.getEffectiveDate().isAfter(clock.getUTCNow()) &&
                                    ((input instanceof ApiEventCancel) || (input instanceof ApiEventChange)));
                        }
                    });
                    futureBaseEvent = (futureApiEvents.size() == 0) ? null : futureApiEvents.iterator().next();
                    break;

                case ADD_ON:
                    final Plan targetAddOnPlan = reloaded.getCurrentPlan();
                    final String baseProductName = (futureBaseEvent instanceof ApiEventChange) ?
                                                   ((ApiEventChange) futureBaseEvent).getEventPlan() : null;

                    final boolean createCancelEvent = (futureBaseEvent != null && targetAddOnPlan != null) &&
                                                      ((futureBaseEvent instanceof ApiEventCancel) ||
                                                       ((!addonUtils.isAddonAvailableFromPlanName(baseProductName, futureBaseEvent.getEffectiveDate(), targetAddOnPlan)) ||
                                                        (addonUtils.isAddonIncludedFromPlanName(baseProductName, futureBaseEvent.getEffectiveDate(), targetAddOnPlan))));

                    if (createCancelEvent && reloaded.getFutureEndDate() == null) {
                        final DateTime now = clock.getUTCNow();
                        final SubscriptionBaseEvent addOnCancelEvent = new ApiEventCancel(new ApiEventBuilder()
                                                                                             .setSubscriptionId(reloaded.getId())
                                                                                             .setActiveVersion(((DefaultSubscriptionBase) reloaded).getActiveVersion())
                                                                                             .setProcessedDate(now)
                                                                                             .setEffectiveDate(futureBaseEvent.getEffectiveDate())
                                                                                             .setRequestedDate(now)
                                                                                             .setCreatedDate(futureBaseEvent.getCreatedDate())
                                                                                                     // This event is only there to indicate the ADD_ON is future canceled, but it is not there
                                                                                                     // on disk until the base plan cancellation becomes effective
                                                                                             .setFromDisk(false));

                        events.add(addOnCancelEvent);
                        // Finally reload subscription with full set of events
                        reloaded = createSubscriptionForInternalUse(cur, events);
                    }
                    break;
                default:
                    break;
            }

            result.add(reloaded);
        }

        return result;
    }

    @Override
    public void migrate(final UUID accountId, final AccountMigrationData accountData, final InternalCallContext context) {
        transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Void>() {
            @Override
            public Void inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final SubscriptionEventSqlDao transactional = entitySqlDaoWrapperFactory.become(SubscriptionEventSqlDao.class);

                for (final BundleMigrationData curBundle : accountData.getData()) {
                    migrateBundleDataFromTransaction(curBundle, transactional, entitySqlDaoWrapperFactory, context);
                }
                return null;
            }
        });
    }

    @Override
    public void repair(final UUID accountId, final UUID bundleId, final List<SubscriptionDataRepair> inRepair, final InternalCallContext context) {
        transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Void>() {
            @Override
            public Void inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final SubscriptionSqlDao transactional = entitySqlDaoWrapperFactory.become(SubscriptionSqlDao.class);

                final SubscriptionEventSqlDao transEventDao = entitySqlDaoWrapperFactory.become(SubscriptionEventSqlDao.class);
                for (final SubscriptionDataRepair cur : inRepair) {
                    transactional.updateForRepair(cur.getId().toString(), cur.getActiveVersion(), cur.getAlignStartDate().toDate(), cur.getBundleStartDate().toDate(), context);
                    for (final SubscriptionBaseEvent event : cur.getInitialEvents()) {
                        transEventDao.updateVersion(event.getId().toString(), event.getActiveVersion(), context);
                    }
                    for (final SubscriptionBaseEvent event : cur.getNewEvents()) {
                        transEventDao.create(new SubscriptionEventModelDao(event), context);
                        if (event.getEffectiveDate().isAfter(clock.getUTCNow())) {
                            recordFutureNotificationFromTransaction(entitySqlDaoWrapperFactory,
                                                                    event.getEffectiveDate(),
                                                                    new SubscriptionNotificationKey(event.getId()),
                                                                    context);
                        }
                    }
                }

                try {
                    // Note: we don't send a requested change event here, but a repair event
                    final RepairSubscriptionInternalEvent busEvent = new DefaultRepairSubscriptionEvent(accountId, bundleId, clock.getUTCNow(),
                                                                                                      context.getAccountRecordId(), context.getTenantRecordId(), context.getUserToken());
                    eventBus.postFromTransaction(busEvent, entitySqlDaoWrapperFactory.getSqlDao());
                } catch (EventBusException e) {
                    log.warn("Failed to post repair subscription event for bundle " + bundleId, e);
                }

                return null;
            }
        });
    }

    @Override
    public void transfer(final UUID srcAccountId, final UUID destAccountId, final BundleMigrationData bundleTransferData,
                         final List<TransferCancelData> transferCancelData, final InternalCallContext fromContext, final InternalCallContext toContext) {

        transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Void>() {
            @Override
            public Void inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final SubscriptionEventSqlDao transactional = entitySqlDaoWrapperFactory.become(SubscriptionEventSqlDao.class);

                // Cancel the subscriptions for the old bundle
                for (final TransferCancelData cancel : transferCancelData) {
                    cancelSubscriptionFromTransaction(cancel.getSubscription(), cancel.getCancelEvent(), entitySqlDaoWrapperFactory, fromContext, 0);
                }

                migrateBundleDataFromTransaction(bundleTransferData, transactional, entitySqlDaoWrapperFactory, toContext);
                return null;
            }
        });
    }

    private DefaultSubscriptionBase createSubscriptionForInternalUse(final SubscriptionBase shellSubscription, final List<SubscriptionBaseEvent> events) {
        final DefaultSubscriptionBase result = new DefaultSubscriptionBase(new SubscriptionBuilder(((DefaultSubscriptionBase) shellSubscription)), null, clock);
        if (events.size() > 0) {
            result.rebuildTransitions(events, catalogService.getFullCatalog());
        }
        return result;
    }

    private SubscriptionBase getBaseSubscription(final UUID bundleId, final boolean rebuildSubscription, final InternalTenantContext context) {
        final List<SubscriptionBase> subscriptions = getSubscriptionFromBundleId(bundleId, context);
        for (final SubscriptionBase cur : subscriptions) {
            if (cur.getCategory() == ProductCategory.BASE) {
                return rebuildSubscription ? buildSubscription(cur, context) : cur;
            }
        }
        return null;
    }


    //
    // Either records a notfication or sends a bus event is operation is immediate
    //
    private void recordBusOrFutureNotificationFromTransaction(final DefaultSubscriptionBase subscription, final SubscriptionBaseEvent event, final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory, final boolean busEvent,
                                                              final int seqId, final InternalCallContext context) {
        if (busEvent) {
            notifyBusOfEffectiveImmediateChange(entitySqlDaoWrapperFactory, subscription, event, seqId, context);
        } else {
            recordFutureNotificationFromTransaction(entitySqlDaoWrapperFactory,
                                                    event.getEffectiveDate(),
                                                    new SubscriptionNotificationKey(event.getId()),
                                                    context);
        }
    }

    //
    // Sends bus notification for event on effecfive date-- only used for operation that happen immediately:
    // - CREATE,
    // - IMM CANCEL or CHANGE
    //
    private void notifyBusOfEffectiveImmediateChange(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory, final DefaultSubscriptionBase subscription,
                                                     final SubscriptionBaseEvent immediateEvent, final int seqId, final InternalCallContext context) {
        try {
            final DefaultSubscriptionBase upToDateSubscription = createSubscriptionWithNewEvent(subscription, immediateEvent);

            final SubscriptionBaseTransitionData transition = upToDateSubscription.getTransitionFromEvent(immediateEvent, seqId);
            final EffectiveSubscriptionInternalEvent busEvent = new DefaultEffectiveSubscriptionEvent(transition, upToDateSubscription.getAlignStartDate(),
                                                                                                      context.getUserToken(),
                                                                                                      context.getAccountRecordId(), context.getTenantRecordId());


            eventBus.postFromTransaction(busEvent, entitySqlDaoWrapperFactory.getSqlDao());
        } catch (EventBusException e) {
            log.warn("Failed to post effective event for subscription " + subscription.getId(), e);
        }
    }

    private void notifyBusOfRequestedChange(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory, final DefaultSubscriptionBase subscription,
                                            final SubscriptionBaseEvent nextEvent, final InternalCallContext context) {
        try {
            eventBus.postFromTransaction(new DefaultRequestedSubscriptionEvent(subscription, nextEvent, context.getAccountRecordId(), context.getTenantRecordId(), context.getUserToken()), entitySqlDaoWrapperFactory.getSqlDao());
        } catch (EventBusException e) {
            log.warn("Failed to post requested change event for subscription " + subscription.getId(), e);
        }
    }

    private void recordFutureNotificationFromTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory, final DateTime effectiveDate,
                                                         final NotificationEvent notificationKey, final InternalCallContext context) {
        try {
            final NotificationQueue subscriptionEventQueue = notificationQueueService.getNotificationQueue(DefaultSubscriptionBaseService.SUBSCRIPTION_SERVICE_NAME,
                                                                                                           DefaultSubscriptionBaseService.NOTIFICATION_QUEUE_NAME);
            subscriptionEventQueue.recordFutureNotificationFromTransaction(entitySqlDaoWrapperFactory.getSqlDao(), effectiveDate, notificationKey, context.getUserToken(), context.getAccountRecordId(), context.getTenantRecordId());
        } catch (NoSuchNotificationQueue e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void migrateBundleDataFromTransaction(final BundleMigrationData bundleTransferData, final SubscriptionEventSqlDao transactional,
                                                  final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory, final InternalCallContext context) throws EntityPersistenceException {

        final SubscriptionSqlDao transSubDao = entitySqlDaoWrapperFactory.become(SubscriptionSqlDao.class);
        final BundleSqlDao transBundleDao = entitySqlDaoWrapperFactory.become(BundleSqlDao.class);

        final DefaultSubscriptionBaseBundle bundleData = bundleTransferData.getData();

        final List<SubscriptionBundleModelDao> existingBundleModels = transBundleDao.getBundlesFromAccountAndKey(bundleData.getAccountId().toString(), bundleData.getExternalKey(), context);
        if (existingBundleModels.size() != 0) {
            log.error(String.format("Attempted to create a bundle for account %s and key %s that already existed, skip...", bundleData.getAccountId().toString(), bundleData.getExternalKey()));
            return;
        }

        for (final SubscriptionMigrationData curSubscription : bundleTransferData.getSubscriptions()) {
            final DefaultSubscriptionBase subData = curSubscription.getData();
            for (final SubscriptionBaseEvent curEvent : curSubscription.getInitialEvents()) {
                transactional.create(new SubscriptionEventModelDao(curEvent), context);
                recordFutureNotificationFromTransaction(entitySqlDaoWrapperFactory,
                                                        curEvent.getEffectiveDate(),
                                                        new SubscriptionNotificationKey(curEvent.getId()),
                                                        context);
            }
            transSubDao.create(new SubscriptionModelDao(subData), context);

            // Notify the Bus of the latest requested change
            final SubscriptionBaseEvent finalEvent = curSubscription.getInitialEvents().get(curSubscription.getInitialEvents().size() - 1);
            notifyBusOfRequestedChange(entitySqlDaoWrapperFactory, subData, finalEvent, context);
        }

        transBundleDao.create(new SubscriptionBundleModelDao(bundleData), context);
    }

    //
    // Creates a copy of the existing subscriptions whose 'transitions' will reflect the new event
    //
    private DefaultSubscriptionBase createSubscriptionWithNewEvent(final DefaultSubscriptionBase subscription, SubscriptionBaseEvent newEvent) {

        final DefaultSubscriptionBase subscriptionWithNewEvent = new DefaultSubscriptionBase(subscription, null, clock);
        final List<SubscriptionBaseEvent> allEvents = new LinkedList<SubscriptionBaseEvent>();
        if (subscriptionWithNewEvent.getEvents() != null) {
            allEvents.addAll(subscriptionWithNewEvent.getEvents());
        }
        allEvents.add(newEvent);
        subscriptionWithNewEvent.rebuildTransitions(allEvents, catalogService.getFullCatalog());
        return subscriptionWithNewEvent;
    }

}
