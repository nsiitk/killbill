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

package com.ning.billing.subscription.api.user;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.billing.api.TestApiListener.NextEvent;
import com.ning.billing.catalog.api.BillingPeriod;
import com.ning.billing.catalog.api.PriceListSet;
import com.ning.billing.subscription.SubscriptionTestSuiteWithEmbeddedDB;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public abstract class TestUserApiRecreate extends SubscriptionTestSuiteWithEmbeddedDB {

    private static final Logger log = LoggerFactory.getLogger(TestUserApiRecreate.class);

    @Test(groups = "slow")
    public void testRecreateWithBPCanceledThroughSubscription() {
        try {
            testCreateAndRecreate(false);
            assertListenerStatus();
        } catch (SubscriptionBaseApiException e) {
            log.error("Unexpected exception", e);
            Assert.fail(e.getMessage());
        }
    }

    @Test(groups = "slow")
    public void testCreateWithBPCanceledFromUserApi() {
        try {
            testCreateAndRecreate(true);
            assertListenerStatus();
        } catch (SubscriptionBaseApiException e) {
            log.error("Unexpected exception", e);
            Assert.fail(e.getMessage());
        }
    }

    private DefaultSubscriptionBase testCreateAndRecreate(final boolean fromUserAPi) throws SubscriptionBaseApiException {
        final DateTime init = clock.getUTCNow();
        final DateTime requestedDate = init.minusYears(1);

        String productName = "Shotgun";
        BillingPeriod term = BillingPeriod.MONTHLY;
        String planSetName = PriceListSet.DEFAULT_PRICELIST_NAME;

        testListener.pushExpectedEvent(NextEvent.PHASE);
        testListener.pushExpectedEvent(NextEvent.CREATE);
        DefaultSubscriptionBase subscription = (DefaultSubscriptionBase) subscriptionInternalApi.createSubscription(bundle.getId(),
                                                                                             testUtil.getProductSpecifier(productName, planSetName, term, null), requestedDate, internalCallContext);
        assertNotNull(subscription);
        assertEquals(subscription.getActiveVersion(), SubscriptionEvents.INITIAL_VERSION);
        assertEquals(subscription.getBundleId(), bundle.getId());
        assertEquals(subscription.getStartDate(), requestedDate);
        assertEquals(productName, subscription.getCurrentPlan().getProduct().getName());

        assertTrue(testListener.isCompleted(5000));

        // CREATE (AGAIN) WITH NEW PRODUCT
        productName = "Pistol";
        term = BillingPeriod.MONTHLY;
        planSetName = PriceListSet.DEFAULT_PRICELIST_NAME;
        try {

            if (fromUserAPi) {
                subscription = (DefaultSubscriptionBase) subscriptionInternalApi.createSubscription(bundle.getId(),
                                                                                    testUtil.getProductSpecifier(productName, planSetName, term, null), requestedDate, internalCallContext);
            } else {
                subscription.recreate(testUtil.getProductSpecifier(productName, planSetName, term, null), requestedDate, callContext);
            }
            Assert.fail("Expected Create API to fail since BP already exists");
        } catch (SubscriptionBaseApiException e) {
            assertTrue(true);
        }

        // NOW CANCEL ADN THIS SHOULD WORK
        testListener.pushExpectedEvent(NextEvent.CANCEL);
        subscription.cancelWithDate(null, callContext);

        testListener.pushExpectedEvent(NextEvent.PHASE);
        testListener.pushExpectedEvent(NextEvent.RE_CREATE);

        // Avoid ordering issue for events at exact same date; this is actually a real good test,
        // we test it at Beatrix level. At this level that would work for sql tests but not for in memory.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }

        if (fromUserAPi) {
            subscription = (DefaultSubscriptionBase) subscriptionInternalApi.createSubscription(bundle.getId(),
                                                                                testUtil.getProductSpecifier(productName, planSetName, term, null), requestedDate, internalCallContext);
        } else {
            subscription.recreate(testUtil.getProductSpecifier(productName, planSetName, term, null), clock.getUTCNow(), callContext);
        }
        assertEquals(subscription.getActiveVersion(), SubscriptionEvents.INITIAL_VERSION);
        assertEquals(subscription.getBundleId(), bundle.getId());
        assertEquals(subscription.getStartDate(), requestedDate);
        assertEquals(productName, subscription.getCurrentPlan().getProduct().getName());

        return subscription;
    }
}
