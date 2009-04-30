/*
 * ====================
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2008-2009 Sun Microsystems, Inc. All rights reserved.     
 * 
 * The contents of this file are subject to the terms of the Common Development 
 * and Distribution License("CDDL") (the "License").  You may not use this file 
 * except in compliance with the License.
 * 
 * You can obtain a copy of the License at 
 * http://IdentityConnectors.dev.java.net/legal/license.txt
 * See the License for the specific language governing permissions and limitations 
 * under the License. 
 * 
 * When distributing the Covered Code, include this CDDL Header Notice in each file
 * and include the License file at identityconnectors/legal/license.txt.
 * If applicable, add the following below this CDDL Header, with the fields 
 * enclosed by brackets [] replaced by your own identifying information: 
 * "Portions Copyrighted [year] [name of copyright owner]"
 * ====================
 */
package org.identityconnectors.ldap.sync.sunds;

import static java.util.Collections.emptyList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.naming.NamingException;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.identityconnectors.framework.common.objects.SyncDelta;
import org.identityconnectors.framework.common.objects.SyncDeltaType;
import org.identityconnectors.framework.common.objects.SyncResultsHandler;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.ldap.LdapConfiguration;
import org.identityconnectors.ldap.LdapConnection;
import org.identityconnectors.ldap.SunDSTestBase;
import org.junit.Test;

public class SunDSChangeLogSyncStrategyTests extends SunDSTestBase {

    private static final Log log = Log.getLog(SunDSChangeLogSyncStrategyTests.class);

    private static final int WAIT_TIMES = 10;
    private static final int WAIT = 500; /* milliseconds */

    private static LdapConnection newConnection(LdapConfiguration config) throws NamingException {
        LdapConnection conn = new LdapConnection(config);
        cleanupBaseContext(conn);
        return conn;
    }

    private List<SyncDelta> doTest(LdapConnection conn, String ldif, int expected) throws NamingException {
        SunDSChangeLogSyncStrategy sync = new SunDSChangeLogSyncStrategy(conn, ObjectClass.ACCOUNT);
        SyncToken token = sync.getLatestSyncToken();

        LdapModifyForTests.modify(conn, ldif);

        OperationOptionsBuilder builder = new OperationOptionsBuilder();
        builder.setAttributesToGet("cn", "sn", "givenName", "uid");
        OperationOptions options = builder.build();

        final List<SyncDelta> result = new ArrayList<SyncDelta>();
        for (int i = 0; i < WAIT_TIMES; i++) {
            sync.sync(token, new SyncResultsHandler() {
                public boolean handle(SyncDelta delta) {
                    result.add(delta);
                    return true;
                }
            }, options);
            // The change log seems to be asynchronous, need to wait.
            // Wait at least once if expecting == 0 (not expecting any deltas),
            // to make sure that we process the change log entry.
            if (result.size() < expected || (expected == 0 && i == 0)) {
                try {
                    log.ok("Going to wait (result size = {0}, expected = {0})", result.size(), expected);
                    Thread.sleep(WAIT);
                } catch (InterruptedException e) {
                    // Ignore.
                }
            } else {
                break;
            }
        };
        return result;
    }

    @Test
    public void testSimple() throws Exception {
        LdapConnection conn = newConnection(newConfiguration());
        String baseContext = conn.getConfiguration().getBaseContexts()[0];

        String entryDN = "uid=foobar," + baseContext;
        List<SyncDelta> result = doTest(conn,
                "dn: " +entryDN + "\n" +
                "changetype: add\n" +
                "objectClass: inetOrgPerson\n" +
                "objectClass: organizationalPerson\n" +
                "objectClass: person\n" +
                "objectClass: top\n" +
                "uid: foobar\n" +
                "cn: Foo Bar\n" +
                "sn: Bar\n", 1);

        assertEquals(1, result.size());
        SyncDelta delta = result.get(0);
        assertEquals(SyncDeltaType.CREATE_OR_UPDATE, delta.getDeltaType());
        ConnectorObject object = delta.getObject();
        assertEquals(new Uid(entryDN), object.getUid());
        assertEquals(new Name(entryDN), object.getName());
        assertEquals(AttributeBuilder.build("uid", "foobar"), object.getAttributeByName("uid"));
        assertEquals(AttributeBuilder.build("cn", "Foo Bar"), object.getAttributeByName("cn"));
        assertEquals(AttributeBuilder.build("sn", "Bar"), object.getAttributeByName("sn"));

        result = doTest(conn,
                "dn: " + entryDN + "\n" +
                "changeType: modrdn\n" +
                "newRdn: cn=Foo Bar", 1);
        entryDN = "cn=Foo Bar," + baseContext;

        assertEquals(1, result.size());
        delta = result.get(0);
        assertEquals(SyncDeltaType.CREATE_OR_UPDATE, delta.getDeltaType());
        object = delta.getObject();
        assertEquals(new Uid(entryDN), object.getUid());
        assertEquals(new Name(entryDN), object.getName());
        assertEquals(AttributeBuilder.build("uid", emptyList()), object.getAttributeByName("uid"));
        assertEquals(AttributeBuilder.build("cn", "Foo Bar"), object.getAttributeByName("cn"));
        assertEquals(AttributeBuilder.build("sn", "Bar"), object.getAttributeByName("sn"));

        result = doTest(conn,
                "dn: " + entryDN + "\n" +
                "changeType: modify\n" +
                "add: cn\n" +
                "cn: Dummy User", 1);

        assertEquals(1, result.size());
        delta = result.get(0);
        assertEquals(SyncDeltaType.CREATE_OR_UPDATE, delta.getDeltaType());
        object = delta.getObject();
        assertEquals(AttributeBuilder.build("cn", "Foo Bar", "Dummy User"), object.getAttributeByName("cn"));

        result = doTest(conn,
                "dn: " + entryDN + "\n" +
                "changeType: modrdn\n" +
                "newRdn: cn=Dummy User\n" +
                "deleteOldRdn: FALSE", 1);
        entryDN = "cn=Dummy User," + baseContext;

        assertEquals(1, result.size());
        delta = result.get(0);
        assertEquals(SyncDeltaType.CREATE_OR_UPDATE, delta.getDeltaType());
        object = delta.getObject();
        assertEquals(new Uid(entryDN), object.getUid());
        assertEquals(new Name(entryDN), object.getName());
        assertEquals(AttributeBuilder.build("cn", "Foo Bar", "Dummy User"), object.getAttributeByName("cn"));

        result = doTest(conn,
                "dn: " + entryDN + "\n" +
                "changeType: modify\n" +
                "delete: cn\n" +
                "cn: Foo Bar", 1);

        assertEquals(1, result.size());
        delta = result.get(0);
        assertEquals(SyncDeltaType.CREATE_OR_UPDATE, delta.getDeltaType());
        object = delta.getObject();
        assertEquals(AttributeBuilder.build("cn", "Dummy User"), object.getAttributeByName("cn"));

        result = doTest(conn,
                "dn: " + entryDN + "\n" +
                "changeType: delete", 1);

        assertEquals(1, result.size());
        delta = result.get(0);
        assertEquals(SyncDeltaType.DELETE, delta.getDeltaType());
        assertEquals(new Uid(entryDN), delta.getUid());
    }

    @Test
    public void testFilterOutByBaseContexts() throws NamingException {
        LdapConfiguration config = newConfiguration();
        String baseContext = config.getBaseContexts()[0];
        config.setBaseContextsToSynchronize("ou=Subcontext," + baseContext);
        LdapConnection conn = newConnection(config);
        testExpectingNoDelta(conn);
    }

    @Test
    public void testFilterOutByModifiersNames() throws NamingException {
        LdapConfiguration config = newConfiguration();
        config.setModifiersNamesToFilterOut("cn=Directory Manager");
        LdapConnection conn = newConnection(config);
        testExpectingNoDelta(conn);
    }

    @Test
    public void testFilterOutByAttributes() throws NamingException {
        LdapConfiguration config = newConfiguration();
        config.setAttributesToSynchronize("telephoneNumber");
        LdapConnection conn = newConnection(config);
        testExpectingNoDelta(conn);
    }

    @Test
    public void testFilterOutByObjectClasses() throws NamingException {
        LdapConfiguration config = newConfiguration();
        config.setObjectClassesToSynchronize("groupOfUniqueNames");
        LdapConnection conn = newConnection(config);
        testExpectingNoDelta(conn);
    }

    @Test
    public void testAccountSynchronizationFilter() throws NamingException {
        LdapConfiguration config = newConfiguration();
        config.setAccountSynchronizationFilter("cn=value");
        LdapConnection conn = newConnection(config);
        testExpectingNoDelta(conn);
    }

    private void testExpectingNoDelta(LdapConnection conn) throws NamingException {
        String baseContext = conn.getConfiguration().getBaseContexts()[0];
        String entryDN = "uid=foobar," + baseContext;
        List<SyncDelta> result = doTest(conn,
                "dn: " +entryDN + "\n" +
                "changetype: add\n" +
                "objectClass: inetOrgPerson\n" +
                "objectClass: organizationalPerson\n" +
                "objectClass: person\n" +
                "objectClass: top\n" +
                "uid: foobar\n" +
                "cn: Foo Bar\n" +
                "sn: Bar\n", 0);
        assertTrue(result.isEmpty());
    }
}