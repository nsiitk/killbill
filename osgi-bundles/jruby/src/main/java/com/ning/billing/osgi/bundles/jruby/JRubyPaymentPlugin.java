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

package com.ning.billing.osgi.bundles.jruby;

import java.math.BigDecimal;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.UUID;

import javax.annotation.Nullable;

import org.jruby.Ruby;
import org.jruby.embed.ScriptingContainer;
import org.jruby.javasupport.JavaEmbedUtils;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.log.LogService;

import com.ning.billing.osgi.api.config.PluginRubyConfig;
import com.ning.billing.payment.api.PaymentMethodPlugin;
import com.ning.billing.payment.plugin.api.PaymentInfoPlugin;
import com.ning.billing.payment.plugin.api.PaymentPluginApi;
import com.ning.billing.payment.plugin.api.PaymentPluginApiException;
import com.ning.billing.payment.plugin.api.RefundInfoPlugin;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.callcontext.TenantContext;

public class JRubyPaymentPlugin extends JRubyPlugin implements PaymentPluginApi {

    private volatile ServiceRegistration<PaymentPluginApi> paymentInfoPluginRegistration;

    public JRubyPaymentPlugin(final PluginRubyConfig config, final ScriptingContainer container, @Nullable final LogService logger) {
        super(config, container, logger);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void startPlugin(final BundleContext context) {
        super.startPlugin(context);

        final Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put("name", pluginMainClass);

        paymentInfoPluginRegistration = (ServiceRegistration<PaymentPluginApi>) context.registerService(PaymentPluginApi.class.getName(), this, props);
    }

    @Override
    public void stopPlugin(final BundleContext context) {
        paymentInfoPluginRegistration.unregister();

        super.stopPlugin(context);
    }

    @Override
    public String getName() {
        return pluginMainClass;
    }

    @Override
    public PaymentInfoPlugin processPayment(final String pluginPaymentMethodKey, final UUID kbPaymentId, final BigDecimal amount, final CallContext context) throws PaymentPluginApiException {
        checkValidPaymentPlugin();
        checkPluginIsRunning();

        final Ruby runtime = getRuntime();
        pluginInstance.callMethod("charge",
                                  JavaEmbedUtils.javaToRuby(runtime, pluginPaymentMethodKey),
                                  JavaEmbedUtils.javaToRuby(runtime, kbPaymentId.toString()),
                                  JavaEmbedUtils.javaToRuby(runtime, amount.longValue() * 100));

        // TODO
        return null;
    }

    @Override
    public PaymentInfoPlugin getPaymentInfo(final UUID kbPaymentId, final TenantContext context) throws PaymentPluginApiException {
        checkValidPaymentPlugin();
        checkPluginIsRunning();

        pluginInstance.callMethod("get_payment_info", JavaEmbedUtils.javaToRuby(getRuntime(), kbPaymentId.toString()));

        // TODO
        return null;
    }

    @Override
    public RefundInfoPlugin processRefund(final UUID kbPaymentId, final BigDecimal refundAmount, final CallContext context) throws PaymentPluginApiException {
        checkValidPaymentPlugin();
        checkPluginIsRunning();

        final Ruby runtime = getRuntime();
        pluginInstance.callMethod("refund",
                                  JavaEmbedUtils.javaToRuby(runtime, kbPaymentId.toString()),
                                  JavaEmbedUtils.javaToRuby(runtime, refundAmount.longValue() * 100));

        // TODO
        return null;
    }

    @Override
    public String addPaymentMethod(final PaymentMethodPlugin paymentMethodProps, final UUID kbAccountId, final boolean setDefault, final CallContext context) throws PaymentPluginApiException {
        checkValidPaymentPlugin();
        checkPluginIsRunning();

        final Ruby runtime = getRuntime();
        pluginInstance.callMethod("add_payment_method",
                                  JavaEmbedUtils.javaToRuby(runtime, kbAccountId.toString()),
                                  JavaEmbedUtils.javaToRuby(runtime, paymentMethodProps));
        if (setDefault) {
            setDefaultPaymentMethod(paymentMethodProps.getExternalPaymentMethodId(), kbAccountId, context);
        }

        // TODO
        return null;
    }

    @Override
    public void deletePaymentMethod(final String pluginPaymentMethodKey, final UUID kbAccountId, final CallContext context) throws PaymentPluginApiException {
        checkValidPaymentPlugin();
        checkPluginIsRunning();

        final Ruby runtime = getRuntime();
        pluginInstance.callMethod("delete_payment_method",
                                  JavaEmbedUtils.javaToRuby(runtime, pluginPaymentMethodKey),
                                  JavaEmbedUtils.javaToRuby(runtime, kbAccountId.toString()));
    }

    @Override
    public void setDefaultPaymentMethod(final String pluginPaymentMethodKey, final UUID kbAccountId, final CallContext context) throws PaymentPluginApiException {
        checkValidPaymentPlugin();
        checkPluginIsRunning();

        final Ruby runtime = getRuntime();
        pluginInstance.callMethod("set_default_payment_method",
                                  JavaEmbedUtils.javaToRuby(runtime, pluginPaymentMethodKey),
                                  JavaEmbedUtils.javaToRuby(runtime, kbAccountId.toString()));
    }
}