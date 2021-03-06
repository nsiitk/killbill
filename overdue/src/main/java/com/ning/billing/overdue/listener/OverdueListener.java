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

package com.ning.billing.overdue.listener;

import java.util.UUID;

import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.billing.ObjectType;
import com.ning.billing.bus.api.BusEvent;
import com.ning.billing.clock.Clock;
import com.ning.billing.overdue.notification.OverdueAsyncBusNotificationKey;
import com.ning.billing.overdue.notification.OverdueAsyncBusNotificationKey.OverdueAsyncBusNotificationAction;
import com.ning.billing.overdue.notification.OverdueAsyncBusNotifier;
import com.ning.billing.overdue.notification.OverduePoster;
import com.ning.billing.overdue.glue.DefaultOverdueModule;
import com.ning.billing.util.callcontext.CallOrigin;
import com.ning.billing.callcontext.InternalCallContext;
import com.ning.billing.util.callcontext.InternalCallContextFactory;
import com.ning.billing.util.callcontext.UserType;
import com.ning.billing.events.ControlTagCreationInternalEvent;
import com.ning.billing.events.ControlTagDeletionInternalEvent;
import com.ning.billing.events.InvoiceAdjustmentInternalEvent;
import com.ning.billing.events.PaymentErrorInternalEvent;
import com.ning.billing.events.PaymentInfoInternalEvent;
import com.ning.billing.util.tag.ControlTagType;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;

public class OverdueListener {

    private final OverdueDispatcher dispatcher;
    private final InternalCallContextFactory internalCallContextFactory;
    private final OverduePoster asyncPoster;
    private final Clock clock;

    private static final Logger log = LoggerFactory.getLogger(OverdueListener.class);

    @Inject
    public OverdueListener(final OverdueDispatcher dispatcher,
                           final Clock clock,
                           @Named(DefaultOverdueModule.OVERDUE_NOTIFIER_ASYNC_BUS_NAMED)final OverduePoster asyncPoster,
                           final InternalCallContextFactory internalCallContextFactory) {
        this.dispatcher = dispatcher;
        this.asyncPoster = asyncPoster;
        this.clock = clock;
        this.internalCallContextFactory = internalCallContextFactory;
    }

    @Subscribe
    public void handle_OVERDUE_ENFORCEMENT_OFF_Insert(final ControlTagCreationInternalEvent event) {
        if (event.getTagDefinition().getName().equals(ControlTagType.OVERDUE_ENFORCEMENT_OFF.toString()) && event.getObjectType() == ObjectType.ACCOUNT) {
            insertBusEventIntoNotificationQueue(event.getObjectId(), event, OverdueAsyncBusNotificationAction.CLEAR);
        }
    }

    @Subscribe
    public void handle_OVERDUE_ENFORCEMENT_OFF_Removal(final ControlTagDeletionInternalEvent event) {
        if (event.getTagDefinition().getName().equals(ControlTagType.OVERDUE_ENFORCEMENT_OFF.toString()) && event.getObjectType() == ObjectType.ACCOUNT) {
            insertBusEventIntoNotificationQueue(event.getObjectId(), event, OverdueAsyncBusNotificationAction.REFRESH);
        }
    }


    @Subscribe
    public void handlePaymentInfoEvent(final PaymentInfoInternalEvent event) {
        log.debug("Received PaymentInfo event {}", event);
        insertBusEventIntoNotificationQueue(event.getAccountId(), event, OverdueAsyncBusNotificationAction.REFRESH);
    }

    @Subscribe
    public void handlePaymentErrorEvent(final PaymentErrorInternalEvent event) {
        log.debug("Received PaymentError event {}", event);
        insertBusEventIntoNotificationQueue(event.getAccountId(), event, OverdueAsyncBusNotificationAction.REFRESH);
    }

    @Subscribe
    public void handleInvoiceAdjustmentEvent(final InvoiceAdjustmentInternalEvent event) {
        log.debug("Received InvoiceAdjustment event {}", event);
        insertBusEventIntoNotificationQueue(event.getAccountId(), event, OverdueAsyncBusNotificationAction.REFRESH);
    }

    private void insertBusEventIntoNotificationQueue(final UUID accountId, final BusEvent event, final OverdueAsyncBusNotificationAction action) {
        final OverdueAsyncBusNotificationKey notificationKey = new OverdueAsyncBusNotificationKey(accountId, action);
        asyncPoster.insertOverdueNotification(accountId, clock.getUTCNow(), OverdueAsyncBusNotifier.OVERDUE_ASYNC_BUS_NOTIFIER_QUEUE, notificationKey, createCallContext(event.getUserToken(), event.getSearchKey1(), event.getSearchKey2()));

    }

    private InternalCallContext createCallContext(final UUID userToken, final Long accountRecordId, final Long tenantRecordId) {
        return internalCallContextFactory.createInternalCallContext(tenantRecordId, accountRecordId, "OverdueService", CallOrigin.INTERNAL, UserType.SYSTEM, userToken);
    }
}
