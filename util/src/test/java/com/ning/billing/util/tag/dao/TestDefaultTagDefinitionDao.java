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

package com.ning.billing.util.tag.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.ning.billing.api.TestApiListener;
import com.ning.billing.api.TestApiListener.NextEvent;
import com.ning.billing.util.UtilTestSuiteWithEmbeddedDB;
import com.ning.billing.events.BusInternalEvent;
import com.ning.billing.events.TagDefinitionInternalEvent;

import com.google.common.eventbus.Subscribe;

public class TestDefaultTagDefinitionDao extends UtilTestSuiteWithEmbeddedDB {

    private TestApiListener eventsListener;


    @Override
    @BeforeMethod(groups = "slow")
    public void beforeMethod() throws Exception {
        super.beforeMethod();
        eventsListener = new TestApiListener(null, idbi);
        eventBus.register(eventsListener);
    }

    @Override
    @AfterMethod(groups = "slow")
    public void afterMethod() throws Exception {
        eventBus.unregister(eventsListener);
        super.afterMethod();
    }


    @Test(groups = "slow")
    public void testCatchEventsOnCreateAndDelete() throws Exception {
        final String definitionName = UUID.randomUUID().toString().substring(0, 5);
        final String description = UUID.randomUUID().toString().substring(0, 5);

        // Make sure we can create a tag definition
        eventsListener.pushExpectedEvent(NextEvent.TAG_DEFINITION);
        final TagDefinitionModelDao createdTagDefinition = tagDefinitionDao.create(definitionName, description, internalCallContext);
        Assert.assertEquals(createdTagDefinition.getName(), definitionName);
        Assert.assertEquals(createdTagDefinition.getDescription(), description);

        Assert.assertTrue(eventsListener.isCompleted(2000));

        // Make sure we can retrieve it via the DAO
        final TagDefinitionModelDao foundTagDefinition = tagDefinitionDao.getByName(definitionName, internalCallContext);
        Assert.assertEquals(foundTagDefinition, createdTagDefinition);

        /*
        // Verify we caught an event on the bus
        final TagDefinitionInternalEvent tagDefinitionFirstEventReceived = eventsListener.getTagDefinitionEvents().get(0);
        Assert.assertEquals(tagDefinitionFirstEventReceived.getTagDefinitionId(), createdTagDefinition.getId());
        Assert.assertEquals(tagDefinitionFirstEventReceived.getTagDefinition().getName(), createdTagDefinition.getName());
        Assert.assertEquals(tagDefinitionFirstEventReceived.getTagDefinition().getDescription(), createdTagDefinition.getDescription());
        Assert.assertEquals(tagDefinitionFirstEventReceived.getBusEventType(), BusInternalEvent.BusInternalEventType.USER_TAGDEFINITION_CREATION);
        Assert.assertEquals(tagDefinitionFirstEventReceived.getUserToken(), internalCallContext.getUserToken());

        */
        // Delete the tag definition
        eventsListener.pushExpectedEvent(NextEvent.TAG_DEFINITION);
        tagDefinitionDao.deleteById(foundTagDefinition.getId(), internalCallContext);
        Assert.assertTrue(eventsListener.isCompleted(2000));

        // Make sure the tag definition is deleted
        Assert.assertNull(tagDefinitionDao.getByName(definitionName, internalCallContext));

        /*
        // Verify we caught an event on the bus
        final TagDefinitionInternalEvent tagDefinitionSecondEventReceived = eventsListener.getTagDefinitionEvents().get(1);
        Assert.assertEquals(tagDefinitionSecondEventReceived.getTagDefinitionId(), createdTagDefinition.getId());
        Assert.assertEquals(tagDefinitionSecondEventReceived.getTagDefinition().getName(), createdTagDefinition.getName());
        Assert.assertEquals(tagDefinitionSecondEventReceived.getTagDefinition().getDescription(), createdTagDefinition.getDescription());
        Assert.assertEquals(tagDefinitionSecondEventReceived.getBusEventType(), BusInternalEvent.BusInternalEventType.USER_TAGDEFINITION_DELETION);
        Assert.assertEquals(tagDefinitionSecondEventReceived.getUserToken(), internalCallContext.getUserToken());
        */
    }

    private static final class EventsListener {

        private final List<BusInternalEvent> events = new ArrayList<BusInternalEvent>();
        private final List<TagDefinitionInternalEvent> tagDefinitionEvents = new ArrayList<TagDefinitionInternalEvent>();

        @Subscribe
        public synchronized void processEvent(final BusInternalEvent event) {
            events.add(event);
        }

        @Subscribe
        public synchronized void processTagDefinitionEvent(final TagDefinitionInternalEvent event) {
            tagDefinitionEvents.add(event);
        }

        public List<BusInternalEvent> getEvents() {
            return events;
        }

        public List<TagDefinitionInternalEvent> getTagDefinitionEvents() {
            return tagDefinitionEvents;
        }
    }
}
