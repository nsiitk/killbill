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

package com.ning.billing.jaxrs.json;

import java.math.BigDecimal;
import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.billing.catalog.api.Currency;
import com.ning.billing.jaxrs.JaxrsTestSuiteNoDB;

import com.google.common.collect.ImmutableList;

public class TestBundleTimelineJson extends JaxrsTestSuiteNoDB {

    @Test(groups = "fast")
    public void testJson() throws Exception {
        final String viewId = UUID.randomUUID().toString();
        final String reason = UUID.randomUUID().toString();

        final BundleJson bundleJson = createBundleWithSubscriptions();
        final InvoiceJson invoiceJson = createInvoice();
        final PaymentJson paymentJson = createPayment(UUID.fromString(invoiceJson.getAccountId()),
                                                                  UUID.fromString(invoiceJson.getInvoiceId()));

        final BundleTimelineJson bundleTimelineJson = new BundleTimelineJson(viewId,
                                                                             bundleJson,
                                                                             ImmutableList.<PaymentJson>of(paymentJson),
                                                                             ImmutableList.<InvoiceJson>of(invoiceJson),
                                                                             reason);

        final String asJson = mapper.writeValueAsString(bundleTimelineJson);
        final BundleTimelineJson fromJson = mapper.readValue(asJson, BundleTimelineJson.class);
        Assert.assertEquals(fromJson, bundleTimelineJson);
    }

    private BundleJson createBundleWithSubscriptions() {
        final String someUUID = UUID.randomUUID().toString();
        final UUID accountId = UUID.randomUUID();
        final UUID bundleId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final String externalKey = UUID.randomUUID().toString();

        final SubscriptionJson entitlementJsonWithEvents = new SubscriptionJson(accountId.toString(), bundleId.toString(), subscriptionId.toString(), externalKey,
                                                                                                    new LocalDate(), someUUID, someUUID, someUUID, someUUID,
                                                                                                    new LocalDate(), new LocalDate(), new LocalDate(), new LocalDate(),
                                                                                                    null, null, null, null);
        return new BundleJson(accountId.toString(), bundleId.toString(), externalKey, ImmutableList.<SubscriptionJson>of(entitlementJsonWithEvents), null);
    }

    private InvoiceJson createInvoice() {
        final UUID accountId = UUID.randomUUID();
        final UUID invoiceId = UUID.randomUUID();
        final BigDecimal invoiceAmount = BigDecimal.TEN;
        final BigDecimal creditAdj = BigDecimal.ONE;
        final BigDecimal refundAdj = BigDecimal.ONE;
        final LocalDate invoiceDate = clock.getUTCToday();
        final LocalDate targetDate = clock.getUTCToday();
        final String invoiceNumber = UUID.randomUUID().toString();
        final BigDecimal balance = BigDecimal.ZERO;

        return new InvoiceJson(invoiceAmount, Currency.USD.toString(), creditAdj, refundAdj, invoiceId.toString(), invoiceDate,
                                     targetDate, invoiceNumber, balance, accountId.toString(), null, null, null, null);
    }

    private PaymentJson createPayment(final UUID accountId, final UUID invoiceId) {
        final UUID paymentId = UUID.randomUUID();
        final Integer paymentNumber = 17;
        final UUID paymentMethodId = UUID.randomUUID();
        final BigDecimal paidAmount = BigDecimal.TEN;
        final BigDecimal amount = BigDecimal.ZERO;
        final DateTime paymentRequestedDate = clock.getUTCNow();
        final DateTime paymentEffectiveDate = clock.getUTCNow();
        final Integer retryCount = Integer.MAX_VALUE;
        final String currency = "USD";
        final String status = UUID.randomUUID().toString();
        final String gatewayErrorCode = "OK";
        final String gatewayErrorMsg = "Excellent...";
        return new PaymentJson(amount, paidAmount, accountId.toString(), invoiceId.toString(), paymentId.toString(), paymentNumber.toString(),
                                     paymentMethodId.toString(), paymentRequestedDate, paymentEffectiveDate, retryCount, currency, status,
                                     gatewayErrorCode, gatewayErrorMsg, null, null, null, null);
    }
}
