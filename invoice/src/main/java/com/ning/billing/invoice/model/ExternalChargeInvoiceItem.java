/*
 * Copyright 2010-2012 Ning, Inc.
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

package com.ning.billing.invoice.model;

import java.math.BigDecimal;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;

import com.ning.billing.catalog.api.Currency;
import com.ning.billing.invoice.api.InvoiceItem;
import com.ning.billing.invoice.api.InvoiceItemType;

public class ExternalChargeInvoiceItem extends InvoiceItemBase {

    public ExternalChargeInvoiceItem(final UUID invoiceId, final UUID accountId, @Nullable final UUID bundleId, @Nullable final String description,
                                     final LocalDate date, final BigDecimal amount, final Currency currency) {
        this(UUID.randomUUID(), invoiceId, accountId, bundleId, description, date, amount, currency);
    }

    public ExternalChargeInvoiceItem(final UUID id, final UUID invoiceId, final UUID accountId, @Nullable final UUID bundleId,
                                     @Nullable final String description, final LocalDate date, final BigDecimal amount, final Currency currency) {
        this(id, null, invoiceId, accountId, bundleId, description, date, amount, currency);
    }

    public ExternalChargeInvoiceItem(final UUID id, @Nullable final DateTime createdDate, final UUID invoiceId, final UUID accountId, @Nullable final UUID bundleId,
                                     @Nullable final String description, final LocalDate date, final BigDecimal amount, final Currency currency) {
        super(id, createdDate, invoiceId, accountId, bundleId, null, description, null, date, null, amount, currency);
    }

    @Override
    public String getDescription() {
        if (getPlanName() == null) {
            return "External charge";
        } else {
            return String.format("%s (external charge)", getPlanName());
        }
    }

    @Override
    public int hashCode() {
        int result = accountId.hashCode();
        result = 31 * result + (subscriptionId != null ? subscriptionId.hashCode() : 0);
        result = 31 * result + (bundleId != null ? bundleId.hashCode() : 0);
        result = 31 * result + (planName != null ? planName.hashCode() : 0);
        result = 31 * result + (phaseName != null ? phaseName.hashCode() : 0);
        result = 31 * result + (startDate != null ? startDate.hashCode() : 0);
        result = 31 * result + (endDate != null ? endDate.hashCode() : 0);
        result = 31 * result + amount.hashCode();
        result = 31 * result + currency.hashCode();
        return result;
    }

    @Override
    public int compareTo(final InvoiceItem item) {
        if (!(item instanceof ExternalChargeInvoiceItem)) {
            return 1;
        }

        final ExternalChargeInvoiceItem that = (ExternalChargeInvoiceItem) item;
        final int compareAccounts = getAccountId().compareTo(that.getAccountId());
        if (compareAccounts == 0) {
            return getStartDate().compareTo(that.getStartDate());
        } else {
            return compareAccounts;
        }
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final ExternalChargeInvoiceItem that = (ExternalChargeInvoiceItem) o;
        if (accountId.compareTo(that.accountId) != 0) {
            return false;
        }
        if (subscriptionId != null ? !subscriptionId.equals(that.subscriptionId) : that.subscriptionId != null) {
            return false;
        }
        if (bundleId != null ? !bundleId.equals(that.bundleId) : that.bundleId != null) {
            return false;
        }
        if (amount != null ? amount.compareTo(that.amount) != 0 : that.amount != null) {
            return false;
        }
        if (currency != that.currency) {
            return false;
        }
        if (startDate != null ? startDate.compareTo(that.startDate) != 0 : that.startDate != null) {
            return false;
        }
        if (endDate != null ? endDate.compareTo(that.endDate) != 0 : that.endDate != null) {
            return false;
        }
        if (phaseName != null ? !phaseName.equals(that.phaseName) : that.phaseName != null) {
            return false;
        }
        if (planName != null ? !planName.equals(that.planName) : that.planName != null) {
            return false;
        }

        return true;
    }

    @Override
    public InvoiceItemType getInvoiceItemType() {
        return InvoiceItemType.EXTERNAL_CHARGE;
    }
}
