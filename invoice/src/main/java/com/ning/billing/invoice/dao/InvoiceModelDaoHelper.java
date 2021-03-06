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

package com.ning.billing.invoice.dao;

import java.math.BigDecimal;

import javax.annotation.Nullable;

import com.ning.billing.invoice.api.InvoiceItem;
import com.ning.billing.invoice.api.InvoicePayment;
import com.ning.billing.invoice.calculator.InvoiceCalculatorUtils;
import com.ning.billing.invoice.model.DefaultInvoicePayment;
import com.ning.billing.invoice.model.InvoiceItemFactory;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;

public class InvoiceModelDaoHelper {

    private InvoiceModelDaoHelper() {}

    public static BigDecimal getBalance(final InvoiceModelDao invoiceModelDao) {
        return InvoiceCalculatorUtils.computeInvoiceBalance(
                Iterables.transform(invoiceModelDao.getInvoiceItems(), new Function<InvoiceItemModelDao, InvoiceItem>() {
                    @Override
                    public InvoiceItem apply(final InvoiceItemModelDao input) {
                        return InvoiceItemFactory.fromModelDao(input);
                    }
                }),
                Iterables.transform(invoiceModelDao.getInvoicePayments(), new Function<InvoicePaymentModelDao, InvoicePayment>() {
                    @Nullable
                    @Override
                    public InvoicePayment apply(final InvoicePaymentModelDao input) {
                        return new DefaultInvoicePayment(input);
                    }
                }));
    }

    public static BigDecimal getCBAAmount(final InvoiceModelDao invoiceModelDao) {
        return InvoiceCalculatorUtils.computeInvoiceAmountCredited(Iterables.transform(invoiceModelDao.getInvoiceItems(), new Function<InvoiceItemModelDao, InvoiceItem>() {
            @Override
            public InvoiceItem apply(final InvoiceItemModelDao input) {
                return InvoiceItemFactory.fromModelDao(input);
            }
        }));
    }
}
