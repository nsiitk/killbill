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

package com.ning.billing.overdue.service;

import java.net.URI;
import java.net.URISyntaxException;

import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.billing.bus.api.PersistentBus.EventBusException;
import com.ning.billing.lifecycle.LifecycleHandlerType;
import com.ning.billing.lifecycle.LifecycleHandlerType.LifecycleLevel;
import com.ning.billing.overdue.notification.OverdueNotifier;
import com.ning.billing.overdue.OverdueProperties;
import com.ning.billing.overdue.OverdueService;
import com.ning.billing.overdue.OverdueUserApi;
import com.ning.billing.overdue.api.DefaultOverdueUserApi;
import com.ning.billing.overdue.config.OverdueConfig;
import com.ning.billing.overdue.glue.DefaultOverdueModule;
import com.ning.billing.overdue.listener.OverdueListener;
import com.ning.billing.overdue.wrapper.OverdueWrapperFactory;
import com.ning.billing.util.config.catalog.XMLLoader;
import com.ning.billing.util.svcsapi.bus.BusService;

import com.google.inject.Inject;

public class DefaultOverdueService implements OverdueService {

    private static final Logger log = LoggerFactory.getLogger(DefaultOverdueService.class);

    public static final String OVERDUE_SERVICE_NAME = "overdue-service";

    private final OverdueUserApi userApi;
    private final OverdueProperties properties;
    private final OverdueNotifier asyncNotifier;
    private final OverdueNotifier checkNotifier;
    private final BusService busService;
    private final OverdueListener listener;
    private final OverdueWrapperFactory factory;

    private OverdueConfig overdueConfig;
    private boolean isConfigLoaded;

    @Inject
    public DefaultOverdueService(
            final OverdueUserApi userApi,
            final OverdueProperties properties,
            @Named(DefaultOverdueModule.OVERDUE_NOTIFIER_CHECK_NAMED) final OverdueNotifier checkNotifier,
            @Named(DefaultOverdueModule.OVERDUE_NOTIFIER_ASYNC_BUS_NAMED) final OverdueNotifier asyncNotifier,
            final BusService busService,
            final OverdueListener listener,
            final OverdueWrapperFactory factory) {
        this.userApi = userApi;
        this.properties = properties;
        this.checkNotifier = checkNotifier;
        this.asyncNotifier = asyncNotifier;
        this.busService = busService;
        this.listener = listener;
        this.factory = factory;
        this.isConfigLoaded = false;
    }

    @Override
    public String getName() {
        return OVERDUE_SERVICE_NAME;
    }

    @Override
    public OverdueUserApi getUserApi() {
        return userApi;
    }

    @LifecycleHandlerType(LifecycleLevel.LOAD_CATALOG)
    public synchronized void loadConfig() throws ServiceException {
        if (!isConfigLoaded) {
            try {
                final URI u = new URI(properties.getConfigURI());
                overdueConfig = XMLLoader.getObjectFromUri(u, OverdueConfig.class);
                // File not found?
                if (overdueConfig == null) {
                    log.warn("Unable to load the overdue config from " + properties.getConfigURI());
                    overdueConfig = new OverdueConfig();
                }

                isConfigLoaded = true;
            } catch (final URISyntaxException e) {
                overdueConfig = new OverdueConfig();
            } catch (final IllegalArgumentException e) {
                overdueConfig = new OverdueConfig();
            } catch (final Exception e) {
                throw new ServiceException(e);
            }

            factory.setOverdueConfig(overdueConfig);
            ((DefaultOverdueUserApi) userApi).setOverdueConfig(overdueConfig);
        }
    }

    @LifecycleHandlerType(LifecycleHandlerType.LifecycleLevel.INIT_SERVICE)
    public void initialize() {
        registerForBus();
        checkNotifier.initialize();
        asyncNotifier.initialize();
    }

    private void registerForBus() {
        try {
            busService.getBus().register(listener);
        } catch (final EventBusException e) {
            log.error("Problem encountered registering OverdueListener on the Event Bus", e);
        }
    }

    @LifecycleHandlerType(LifecycleLevel.START_SERVICE)
    public void start() {
        checkNotifier.start();
        asyncNotifier.start();
    }

    @LifecycleHandlerType(LifecycleLevel.STOP_SERVICE)
    public void stop() {
        try {
            busService.getBus().unregister(listener);
        } catch (final EventBusException e) {
            log.error("Problem encountered registering OverdueListener on the Event Bus", e);
        }
        checkNotifier.stop();
        asyncNotifier.stop();
    }
}
