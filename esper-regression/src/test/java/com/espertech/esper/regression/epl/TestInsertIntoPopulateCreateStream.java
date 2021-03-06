/*
 ***************************************************************************************
 *  Copyright (C) 2006 EsperTech, Inc. All rights reserved.                            *
 *  http://www.espertech.com/esper                                                     *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 ***************************************************************************************
 */
package com.espertech.esper.regression.epl;

import com.espertech.esper.client.*;
import com.espertech.esper.client.scopetest.EPAssertionUtil;
import com.espertech.esper.client.scopetest.SupportUpdateListener;
import com.espertech.esper.metrics.instrumentation.InstrumentationHelper;
import com.espertech.esper.supportregression.client.SupportConfigFactory;
import com.espertech.esper.util.EventRepresentationChoice;
import junit.framework.TestCase;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

import java.util.Collections;
import java.util.Map;

import static org.apache.avro.SchemaBuilder.record;

public class TestInsertIntoPopulateCreateStream extends TestCase
{
    private EPServiceProvider epService;
    private SupportUpdateListener listener;

    public void setUp()
    {
        Configuration configuration = SupportConfigFactory.getConfiguration();
        epService = EPServiceProviderManager.getDefaultProvider(configuration);
        epService.initialize();
        if (InstrumentationHelper.ENABLED) { InstrumentationHelper.startTest(epService, this.getClass(), getName());}
        listener = new SupportUpdateListener();
    }

    protected void tearDown() throws Exception {
        if (InstrumentationHelper.ENABLED) { InstrumentationHelper.endTest();}
        listener = null;
    }

    public void testCreateStream() {
        for (EventRepresentationChoice rep : EventRepresentationChoice.values()) {
            runAssertionCreateStream(rep);
        }

        for (EventRepresentationChoice rep : EventRepresentationChoice.values()) {
            runAssertPopulateFromNamedWindow(rep);
        }

        runAssertionObjectArrPropertyReorder();
    }

    private void runAssertionObjectArrPropertyReorder() {
        epService.getEPAdministrator().createEPL("create objectarray schema MyInner (p_inner string)");
        epService.getEPAdministrator().createEPL("create objectarray schema MyOATarget (unfilled string, p0 string, p1 string, i0 MyInner)");
        epService.getEPAdministrator().createEPL("create objectarray schema MyOASource (p0 string, p1 string, i0 MyInner)");
        epService.getEPAdministrator().createEPL("insert into MyOATarget select p0, p1, i0, null as unfilled from MyOASource");
        epService.getEPAdministrator().createEPL("select * from MyOATarget").addListener(listener);

        epService.getEPRuntime().sendEvent(new Object[] {"p0value", "p1value", new Object[] {"i"}}, "MyOASource");
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), "p0,p1".split(","), new Object[] {"p0value", "p1value"});
    }

    private void runAssertPopulateFromNamedWindow(EventRepresentationChoice type) {
        epService.getEPAdministrator().createEPL("create " + type.getOutputTypeCreateSchemaName() + " schema Node(nid string)");
        epService.getEPAdministrator().createEPL("create window NodeWindow#unique(nid) as Node");
        epService.getEPAdministrator().createEPL("insert into NodeWindow select * from Node");
        epService.getEPAdministrator().createEPL("create " + type.getOutputTypeCreateSchemaName() + " schema NodePlus(npid string, node Node)");

        EPStatement stmt = epService.getEPAdministrator().createEPL("insert into NodePlus select 'E1' as npid, n1 as node from NodeWindow n1");
        stmt.addListener(listener);

        if (type.isObjectArrayEvent()) {
            epService.getEPRuntime().sendEvent(new Object[] {"n1"}, "Node");
        }
        else if (type.isMapEvent()){
            epService.getEPRuntime().sendEvent(Collections.singletonMap("nid", "n1"), "Node");
        }
        else if (type.isAvroEvent()){
            GenericRecord genericRecord = new GenericData.Record(record("name").fields().requiredString("nid").endRecord());
            genericRecord.put("nid", "n1");
            epService.getEPRuntime().sendEventAvro(genericRecord, "Node");
        }
        else {
            fail();
        }
        EventBean event = listener.assertOneGetNewAndReset();
        assertEquals("E1", event.get("npid"));
        assertEquals("n1", event.get("node.nid"));
        EventBean fragment = (EventBean) event.getFragment("node");
        assertEquals("Node", fragment.getEventType().getName());

        epService.getEPAdministrator().destroyAllStatements();
        epService.getEPAdministrator().getConfiguration().removeEventType("Node", true);
        epService.getEPAdministrator().getConfiguration().removeEventType("NodePlus", true);
        epService.getEPAdministrator().getConfiguration().removeEventType("NodeWindow", true);
    }

    public void testCreateStreamTwo() {
        for (EventRepresentationChoice rep : EventRepresentationChoice.values()) {
            runAssertionCreateStreamTwo(rep);
        }
    }

    private void runAssertionCreateStream(EventRepresentationChoice eventRepresentationEnum)
    {
        epService.getEPAdministrator().createEPL(eventRepresentationEnum.getAnnotationText() + " create schema MyEvent(myId int)");
        epService.getEPAdministrator().createEPL(eventRepresentationEnum.getAnnotationText() + " create schema CompositeEvent(c1 MyEvent, c2 MyEvent, rule string)");
        epService.getEPAdministrator().createEPL("insert into MyStream select c, 'additionalValue' as value from MyEvent c");
        epService.getEPAdministrator().createEPL("insert into CompositeEvent select e1.c as c1, e2.c as c2, '4' as rule " +
                "from pattern [e1=MyStream -> e2=MyStream]");
        epService.getEPAdministrator().createEPL(eventRepresentationEnum.getAnnotationText() + " @Name('Target') select * from CompositeEvent");
        epService.getEPAdministrator().getStatement("Target").addListener(listener);

        if (eventRepresentationEnum.isObjectArrayEvent()) {
            epService.getEPRuntime().sendEvent(makeEvent(10).values().toArray(), "MyEvent");
            epService.getEPRuntime().sendEvent(makeEvent(11).values().toArray(), "MyEvent");
        }
        else if (eventRepresentationEnum.isMapEvent()) {
            epService.getEPRuntime().sendEvent(makeEvent(10), "MyEvent");
            epService.getEPRuntime().sendEvent(makeEvent(11), "MyEvent");
        }
        else if (eventRepresentationEnum.isAvroEvent()) {
            epService.getEPRuntime().sendEventAvro(makeEventAvro(10), "MyEvent");
            epService.getEPRuntime().sendEventAvro(makeEventAvro(11), "MyEvent");
        }
        else {
            fail();
        }
        EventBean theEvent = listener.assertOneGetNewAndReset();
        assertEquals(10, theEvent.get("c1.myId"));
        assertEquals(11, theEvent.get("c2.myId"));
        assertEquals("4", theEvent.get("rule"));

        epService.initialize();
    }

    private void runAssertionCreateStreamTwo(EventRepresentationChoice eventRepresentationEnum)
    {
        epService.getEPAdministrator().createEPL(eventRepresentationEnum.getAnnotationText() + " create schema MyEvent(myId int)");
        epService.getEPAdministrator().createEPL(eventRepresentationEnum.getAnnotationText() + " create schema AllMyEvent as (myEvent MyEvent, class String, reverse boolean)");
        epService.getEPAdministrator().createEPL(eventRepresentationEnum.getAnnotationText() + " create schema SuspectMyEvent as (myEvent MyEvent, class String)");

        EPStatement stmtOne = epService.getEPAdministrator().createEPL("insert into AllMyEvent " +
                                                 "select c as myEvent, 'test' as class, false as reverse " +
                                                 "from MyEvent(myId=1) c");
        stmtOne.addListener(listener);
        assertTrue(eventRepresentationEnum.matchesClass(stmtOne.getEventType().getUnderlyingType()));

        EPStatement stmtTwo = epService.getEPAdministrator().createEPL("insert into SuspectMyEvent " +
                                                 "select c.myEvent as myEvent, class " +
                                                 "from AllMyEvent(not reverse) c");
        SupportUpdateListener listenerTwo = new SupportUpdateListener();
        stmtTwo.addListener(listenerTwo);

        if (eventRepresentationEnum.isObjectArrayEvent()) {
            epService.getEPRuntime().sendEvent(makeEvent(1).values().toArray(), "MyEvent");
        }
        else if (eventRepresentationEnum.isMapEvent()){
            epService.getEPRuntime().sendEvent(makeEvent(1), "MyEvent");
        }
        else if (eventRepresentationEnum.isAvroEvent()){
            epService.getEPRuntime().sendEventAvro(makeEventAvro(1), "MyEvent");
        }
        else {
            fail();
        }

        assertCreateStreamTwo(eventRepresentationEnum, listener.assertOneGetNewAndReset(), stmtOne);
        assertCreateStreamTwo(eventRepresentationEnum, listenerTwo.assertOneGetNewAndReset(), stmtTwo);

        epService.initialize();
    }

    private void assertCreateStreamTwo(EventRepresentationChoice eventRepresentationEnum, EventBean eventBean, EPStatement statement) {
        if (eventRepresentationEnum.isAvroEvent()) {
            assertEquals(1, eventBean.get("myEvent.myId"));
        }
        else {
            assertTrue(eventBean.get("myEvent") instanceof EventBean);
            assertEquals(1, ((EventBean)eventBean.get("myEvent")).get("myId"));
        }
        assertNotNull(statement.getEventType().getFragmentType("myEvent"));
    }

    private Map<String, Object> makeEvent(int myId) {
        return Collections.<String, Object>singletonMap("myId", myId);
    }

    private GenericData.Record makeEventAvro(int myId) {
        Schema schema = record("schema").fields().requiredInt("myId").endRecord();
        GenericData.Record record = new GenericData.Record(schema);
        record.put("myId", myId);
        return record;
    }
}
