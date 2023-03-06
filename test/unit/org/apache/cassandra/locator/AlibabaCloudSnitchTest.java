/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.locator;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.commitlog.CommitLog;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.gms.ApplicationState;
import org.apache.cassandra.gms.Gossiper;
import org.apache.cassandra.gms.VersionedValue;
import org.apache.cassandra.locator.AbstractCloudMetadataServiceConnector.DefaultCloudMetadataServiceConnector;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.Pair;

import static org.apache.cassandra.ServerTestUtils.cleanup;
import static org.apache.cassandra.ServerTestUtils.mkdirs;
import static org.apache.cassandra.config.CassandraRelevantProperties.GOSSIP_DISABLE_THREAD_VALIDATION;
import static org.apache.cassandra.locator.AbstractCloudMetadataServiceConnector.METADATA_URL_PROPERTY;
import static org.apache.cassandra.locator.AlibabaCloudSnitch.DEFAULT_METADATA_SERVICE_URL;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

public class AlibabaCloudSnitchTest
{
    static String az;

    @BeforeClass
    public static void setup() throws Exception
    {
        GOSSIP_DISABLE_THREAD_VALIDATION.setBoolean(true);
        DatabaseDescriptor.daemonInitialization();
        CommitLog.instance.start();
        CommitLog.instance.segmentManager.awaitManagementTasksCompletion();
        mkdirs();
        cleanup();
        Keyspace.setInitialized();
        StorageService.instance.initServer();
    }

    @Test
    public void testRac() throws IOException, ConfigurationException
    {
        az = "cn-hangzhou-f";

        DefaultCloudMetadataServiceConnector spiedConnector = spy(new DefaultCloudMetadataServiceConnector(
        new SnitchProperties(Pair.create(METADATA_URL_PROPERTY, DEFAULT_METADATA_SERVICE_URL))));

        doReturn(az).when(spiedConnector).apiCall(any());

        AlibabaCloudSnitch snitch = new AlibabaCloudSnitch(spiedConnector);
        InetAddressAndPort local = InetAddressAndPort.getByName("127.0.0.1");
        InetAddressAndPort nonlocal = InetAddressAndPort.getByName("127.0.0.7");

        Map<ApplicationState, VersionedValue> stateMap = new EnumMap<>(ApplicationState.class);
        stateMap.put(ApplicationState.DC, StorageService.instance.valueFactory.datacenter("cn-shanghai"));
        stateMap.put(ApplicationState.RACK, StorageService.instance.valueFactory.datacenter("a"));
        Gossiper.instance.getEndpointStateForEndpoint(nonlocal).addApplicationStates(stateMap);

        assertEquals("cn-shanghai", snitch.getDatacenter(nonlocal));
        assertEquals("a", snitch.getRack(nonlocal));

        assertEquals("cn-hangzhou", snitch.getDatacenter(local));
        assertEquals("f", snitch.getRack(local));
    }

    @Test
    public void testNewRegions() throws IOException, ConfigurationException
    {
        az = "us-east-1a";
        DefaultCloudMetadataServiceConnector spiedConnector = spy(new DefaultCloudMetadataServiceConnector(
        new SnitchProperties(Pair.create(METADATA_URL_PROPERTY, DEFAULT_METADATA_SERVICE_URL))));

        doReturn(az).when(spiedConnector).apiCall(any());

        AlibabaCloudSnitch snitch = new AlibabaCloudSnitch(spiedConnector);
        InetAddressAndPort local = InetAddressAndPort.getByName("127.0.0.1");
        assertEquals("us-east", snitch.getDatacenter(local));
        assertEquals("1a", snitch.getRack(local));
    }

    @AfterClass
    public static void tearDown()
    {
        StorageService.instance.stopClient();
    }
}
