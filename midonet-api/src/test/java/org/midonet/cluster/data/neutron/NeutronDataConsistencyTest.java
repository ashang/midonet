/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.data.neutron;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.rest_api.neutron.models.Network;
import org.midonet.cluster.rest_api.neutron.models.Port;
import org.midonet.cluster.rest_api.neutron.models.Router;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.l4lb.MappingStatusException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

import java.util.UUID;

public final class NeutronDataConsistencyTest extends NeutronPluginTest {

    private DataClient dataClient;
    private ZkManager zk;
    private PathBuilder pb;

    @Before
    public void setUp() throws Exception {

        super.setUp();

        this.dataClient = this.injector.getInstance(DataClient.class);
        this.zk = this.injector.getInstance(ZkManager.class);
        this.pb = this.injector.getInstance(PathBuilder.class);
    }

    /**
     * Test that NeutronPlugin allows deletion of a router that does not have
     * corresponding MidoNet router
     */
    @Test
    public void testDeleteMidonetRouterThenNeutronRouter()
        throws SerializationException, StateAccessException {

        // Delete the MidoNet router
        this.dataClient.routersDelete(router.id);

        // Try deleting the same router using NeutronPlugin - it should not
        // fail
        this.plugin.deleteRouter(router.id);

        // Get the router and make sure it's gone
        Router r = this.plugin.getRouter(router.id);
        Assert.assertNull(r);
    }

    /**
     * Test that NeutronPlugin allows deletion of a network that does not have
     * corresponding MidoNet bridge
     */
    @Test
    public void testDeleteMidonetBridgeThenNeutronNetwork()
        throws SerializationException, StateAccessException {

        // Delete the MidoNet bridge
        this.dataClient.bridgesDelete(network.id);

        // Try deleting the same network using NeutronPlugin - it should not
        // fail
        this.plugin.deleteNetwork(network.id);

        // Get the network and make sure it's gone
        Network n = this.plugin.getNetwork(network.id);
        Assert.assertNull(n);
    }

    /**
     * Test that NeutronPlugin allows deletion of a port that does not have
     * corresponding MidoNet port
     */
    @Test
    public void testDeleteMidonetPortThenNeutronPort()
        throws SerializationException, StateAccessException,
               Rule.RuleIndexOutOfBoundsException {

        // Delete the MidoNet port
        this.dataClient.portsDelete(port.id);

        // Try deleting the same port using NeutronPlugin - it should not fail
        this.plugin.deletePort(port.id);

        // Get the port and make sure it's gone
        Port p = this.plugin.getPort(port.id);
        Assert.assertNull(p);
    }

    /**
     * Test that deleting the LoadBalancer object will also delete any
     * corresponding pool-health monitor mappings
     */
    @Test
    public void testDeleteLoadBalancer()
            throws SerializationException, StateAccessException,
            MappingStatusException, Rule.RuleIndexOutOfBoundsException {

        String path = pb.getPoolHealthMonitorMappingsPath(pool.id,
                healthMonitor.id);

        // make sure the path exists
        Assert.assertTrue(zk.exists(path));

        UUID lbId = dataClient.poolGet(pool.id).getLoadBalancerId();

        dataClient.loadBalancerDelete(lbId);

        // Check that the mapping path is cleaned up
        Assert.assertFalse(zk.exists(path));

        Assert.assertNull(dataClient.poolGet(pool.id));
    }
}
