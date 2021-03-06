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

package com.ning.billing.events;


import com.ning.billing.bus.api.BusEvent;

public interface BusInternalEvent extends BusEvent {

    public enum BusInternalEventType {
        ACCOUNT_CHANGE,
        ACCOUNT_CREATE,
        BLOCKING_STATE,
        BUNDLE_REPAIR,
        CONTROL_TAGDEFINITION_CREATION,
        CONTROL_TAGDEFINITION_DELETION,
        CONTROL_TAG_CREATION,
        CONTROL_TAG_DELETION,
        CUSTOM_FIELD_CREATION,
        CUSTOM_FIELD_DELETION,
        ENTITLEMENT_TRANSITION,
        INVOICE_ADJUSTMENT,
        INVOICE_CREATION,
        INVOICE_EMPTY,
        OVERDUE_CHANGE,
        PAYMENT_ERROR,
        PAYMENT_INFO,
        SUBSCRIPTION_TRANSITION,
        USER_TAGDEFINITION_CREATION,
        USER_TAGDEFINITION_DELETION,
        USER_TAG_CREATION,
        USER_TAG_DELETION
    }

    public BusInternalEventType getBusEventType();

}
