/*
 * Copyright 2010-2011 Ning, Inc.
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

package com.ning.billing.payment.dao;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.payment.api.PaymentAttempt;
import com.ning.billing.payment.api.PaymentInfo;

public class MockPaymentDao implements PaymentDao {
    private final Map<String, PaymentInfo> payments = new ConcurrentHashMap<String, PaymentInfo>();
    private final Map<UUID, PaymentAttempt> paymentAttempts = new ConcurrentHashMap<UUID, PaymentAttempt>();

    @Override
    public PaymentAttempt getPaymentAttemptForPaymentId(String paymentId) {
        for (PaymentAttempt paymentAttempt : paymentAttempts.values()) {
            if (paymentId.equals(paymentAttempt.getPaymentId())) {
                return paymentAttempt;
            }
        }
        return null;
    }

    @Override
    public PaymentAttempt createPaymentAttempt(Invoice invoice) {
        PaymentAttempt paymentAttempt = new PaymentAttempt(UUID.randomUUID(), invoice);
        paymentAttempts.put(paymentAttempt.getPaymentAttemptId(), paymentAttempt);
        return paymentAttempt;
    }

    @Override
    public void savePaymentInfo(PaymentInfo paymentInfo) {
        payments.put(paymentInfo.getPaymentId(), paymentInfo);
    }

    @Override
    public void updatePaymentAttemptWithPaymentId(UUID paymentAttemptId, String paymentId) {
        PaymentAttempt existingPaymentAttempt = paymentAttempts.get(paymentAttemptId);

        if (existingPaymentAttempt != null) {
            paymentAttempts.put(existingPaymentAttempt.getPaymentAttemptId(),
                                existingPaymentAttempt.cloner().setPaymentId(paymentId).build());
        }
    }

    @Override
    public PaymentAttempt getPaymentAttemptForInvoiceId(String invoiceId) {
        for (PaymentAttempt paymentAttempt : paymentAttempts.values()) {
            if (invoiceId.equals(paymentAttempt.getInvoiceId())) {
                return paymentAttempt;
            }
        }
        return null;
    }

    @Override
    public void updatePaymentInfo(String paymentMethodType, String paymentId, String cardType, String cardCountry) {
        // TODO Auto-generated method stub

    }

}
