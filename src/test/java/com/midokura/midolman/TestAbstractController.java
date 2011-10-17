/* Copyright 2011 Midokura Inc. */

package com.midokura.midolman;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPhysicalPort.OFPortConfig;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.Data;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.LLDP;
import com.midokura.midolman.packets.LLDPTLV;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.TCP;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.util.Net;


class AbstractControllerTester extends AbstractController {
    public List<OFPhysicalPort> portsAdded;
    public List<OFPhysicalPort> portsRemoved;
    public int numClearCalls;
    short flowExpireSeconds;
    short idleFlowExpireSeconds;
    OFAction[] flowActions = {};
 
    public AbstractControllerTester(
            int datapathId,
            UUID switchUuid,
            int greKey,
            OpenvSwitchDatabaseConnection ovsdb,
            PortToIntNwAddrMap dict,
            short flowExpireSeconds,
            long idleFlowExpireMillis,
            IntIPv4 internalIp) {
        super(datapathId, switchUuid, greKey, ovsdb, dict, internalIp, 
              "midonet");
        portsAdded = new ArrayList<OFPhysicalPort>();
        portsRemoved = new ArrayList<OFPhysicalPort>();
        numClearCalls = 0;
        this.flowExpireSeconds = flowExpireSeconds;
        this.idleFlowExpireSeconds = (short)(idleFlowExpireMillis/1000);
    }

    @Override 
    public void onPacketIn(int bufferId, int totalLen, short inPort,
                           byte[] data) { 
        Ethernet frame = new Ethernet();
        frame.deserialize(data, 0, data.length);
        OFMatch match = createMatchFromPacket(frame, inPort);
        addFlowAndPacketOut(match, 1040, idleFlowExpireSeconds,
                            flowExpireSeconds, (short)1000, bufferId, true, 
                            false, false, flowActions, inPort, data);
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie,
            short priority, OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) { }

    public void clear() {
        portsAdded = new ArrayList<OFPhysicalPort>();
        portsRemoved = new ArrayList<OFPhysicalPort>();
        numClearCalls++;
    }

    @Override
    protected void addPort(OFPhysicalPort portDesc, short portNum) { 
        assertEquals(portDesc.getPortNumber(), portNum);
        portsAdded.add(portDesc);
    }

    @Override
    protected void deletePort(OFPhysicalPort portDesc) { 
        portsRemoved.add(portDesc);
    }

    @Override 
    protected void portMoved(UUID portUuid, IntIPv4 oldAddr, IntIPv4 newAddr) {
        // Do nothing.
    }

    public void setFeatures(OFFeaturesReply features) {
        ((MockControllerStub) controllerStub).setFeatures(features);
    }

    @Override
    public String makeGREPortName(IntIPv4 a) {
        return super.makeGREPortName(a);
    }

    @Override
    public IntIPv4 peerIpOfGrePortName(String s) {
        return super.peerIpOfGrePortName(s);
    }
}


public class TestAbstractController {

    private AbstractControllerTester controller;

    private OFPhysicalPort port1;
    private OFPhysicalPort port2;
    private OFPhysicalPort port3;
    private UUID port1uuid;
    private UUID port2uuid;
    private UUID port3uuid;
    private int dp_id;
    private MockOpenvSwitchDatabaseConnection ovsdb;
    private PortToIntNwAddrMap portLocMap;
    private MockDirectory mockDir;
    private MockControllerStub controllerStub = new MockControllerStub();

    public Logger log = LoggerFactory.getLogger(TestAbstractController.class);

    @Before
    public void setUp() {
        dp_id = 43;
        ovsdb = new MockOpenvSwitchDatabaseConnection();

        mockDir = new MockDirectory();
        portLocMap = new PortToIntNwAddrMap(mockDir);

        IntIPv4 publicIp = IntIPv4.fromString("192.168.1.50");
        controller = new AbstractControllerTester(
                             dp_id /* datapathId */,
                             UUID.randomUUID() /* switchUuid */,
                             0xe1234 /* greKey */,
                             ovsdb /* ovsdb */,
                             portLocMap /* portLocationMap */,
                             (short)300 /* flowExpireSeconds */,
                             60 * 1000 /* idleFlowExpireMillis */,
                             publicIp /* internalIp */);
        controller.setControllerStub(controllerStub);

        port1 = new OFPhysicalPort();
        port1.setPortNumber((short) 37);
        port1.setHardwareAddress(new byte[] { 10, 12, 13, 14, 15, 37 });
        port1uuid = UUID.randomUUID();
        ovsdb.setPortExternalId(dp_id, 37, "midonet", port1uuid.toString());

        port2 = new OFPhysicalPort();
        port2.setPortNumber((short) 47);
        port2.setHardwareAddress(new byte[] { 10, 12, 13, 14, 15, 47 });
        port2.setName("tne12340a001122");
        port2uuid = UUID.randomUUID();
        ovsdb.setPortExternalId(dp_id, 47, "midonet", port2uuid.toString());

        port3 = new OFPhysicalPort();
        port3.setPortNumber((short) 57);
        port3.setHardwareAddress(new byte[] { 10, 12, 13, 14, 15, 57 });
        port3.setConfig(AbstractController.portDownFlag);
        port3uuid = UUID.randomUUID();
        ovsdb.setPortExternalId(dp_id, 57, "midonet", port3uuid.toString());

        controller.onPortStatus(port1, OFPortReason.OFPPR_ADD);
        controller.onPortStatus(port2, OFPortReason.OFPPR_ADD);
        controller.onPortStatus(port3, OFPortReason.OFPPR_ADD);
    }

    @Test
    public void testPortMap() {
        assertEquals(37, controller.portUuidToNumber(port1uuid));
        assertEquals(47, controller.portUuidToNumber(port2uuid));
        assertFalse(controller.isTunnelPortNum(37));
        assertTrue(controller.isTunnelPortNum(47));
        assertEquals(null, controller.peerOfTunnelPortNum(37));
        assertEquals(0x0a001122, controller.peerOfTunnelPortNum(47).address);
    }

    @Test
    public void testConnectionMade() {
        OFFeaturesReply features = new OFFeaturesReply();
        ArrayList<OFPhysicalPort> portList = new ArrayList<OFPhysicalPort>();
        portList.add(port1);    // Regular port, gets recorded.
        portList.add(port2);    // Tunnel port, gets deleted.
        features.setPorts(portList);
        controller.setFeatures(features);
        controller.onConnectionLost();
        assertArrayEquals(new OFPhysicalPort[] { },
                          controller.portsAdded.toArray());
        assertArrayEquals(new String[] { },
                          ovsdb.deletedPorts.toArray());
        MockControllerStub stub = 
                (MockControllerStub) controller.controllerStub;
        assertEquals(0, stub.deletedFlows.size());
        controller.onConnectionMade();
        assertArrayEquals(new OFPhysicalPort[] { port1 },
                          controller.portsAdded.toArray());
        assertArrayEquals(new String[] { port2.getName() },
                          ovsdb.deletedPorts.toArray());
        assertEquals(1, stub.deletedFlows.size());
        assertEquals(OFMatch.OFPFW_ALL, 
                     stub.deletedFlows.get(0).match.getWildcards());
    }

    @Test
    public void testClearAdd() {
        assertArrayEquals(new OFPhysicalPort[] { port1, port2 },
                          controller.portsAdded.toArray());
        assertEquals(0, controller.numClearCalls);
        controller.onConnectionLost();
        assertEquals(1, controller.numClearCalls);
        assertArrayEquals(new OFPhysicalPort[] { },
                          controller.portsAdded.toArray());
        controller.onPortStatus(port1, OFPortReason.OFPPR_ADD);
        controller.onPortStatus(port2, OFPortReason.OFPPR_ADD);
        assertArrayEquals(new OFPhysicalPort[] { port1, port2 },
                          controller.portsAdded.toArray());
        assertEquals(port1uuid, controller.portNumToUuid.get(37));
        assertEquals(port2uuid, controller.portNumToUuid.get(47));
        assertNull(controller.peerOfTunnelPortNum(37));
        assertEquals("10.0.17.34",
                     controller.peerOfTunnelPortNum(47).toString());
    }

    @Test
    public void testBringPortUp() {
        assertArrayEquals(new OFPhysicalPort[] { port1, port2 },
                          controller.portsAdded.toArray());
        port3.setConfig(0);
        controller.onPortStatus(port3, OFPortReason.OFPPR_MODIFY);
        assertArrayEquals(new OFPhysicalPort[] { port1, port2, port3 },
                          controller.portsAdded.toArray());
    }

    @Test
    public void testModifyPort() {
        port2.setName("tne12340a001123");
        UUID port2newUuid = UUID.randomUUID();
        ovsdb.setPortExternalId(dp_id, 47, "midonet", port2newUuid.toString());
        controller.onPortStatus(port2, OFPortReason.OFPPR_MODIFY);
        assertEquals(port2newUuid, controller.portNumToUuid.get(47));
        assertEquals("10.0.17.35",
                     controller.peerOfTunnelPortNum(47).toString());
    }

    @Test
    public void testModifyDownPort() {
        assertArrayEquals(new OFPhysicalPort[] { },
                          controller.portsRemoved.toArray());
        assertArrayEquals(new OFPhysicalPort[] { port1, port2 },
                          controller.portsAdded.toArray());
        port3.setName("tne12340a001123");
        UUID port3newUuid = UUID.randomUUID();
        ovsdb.setPortExternalId(dp_id, 57, "midonet", port3newUuid.toString());
        controller.onPortStatus(port3, OFPortReason.OFPPR_MODIFY);
        assertNull(controller.portNumToUuid.get(57));
        assertNull(controller.peerOfTunnelPortNum(57));
        assertArrayEquals(new OFPhysicalPort[] { },
                          controller.portsRemoved.toArray());
        assertArrayEquals(new OFPhysicalPort[] { port1, port2 },
                          controller.portsAdded.toArray());
    }

    @Test
    public void testDeletePort() {
        assertArrayEquals(new OFPhysicalPort[] { },
                          controller.portsRemoved.toArray());
        assertTrue(controller.portNumToUuid.containsKey(37));
        assertNull(controller.peerOfTunnelPortNum(37));
        controller.onPortStatus(port1, OFPortReason.OFPPR_DELETE);
        assertArrayEquals(new OFPhysicalPort[] { port1 },
                          controller.portsRemoved.toArray());
        assertFalse(controller.portNumToUuid.containsKey(37));
        assertTrue(controller.portNumToUuid.containsKey(47));
        assertNotNull(controller.peerOfTunnelPortNum(47));
        controller.onPortStatus(port2, OFPortReason.OFPPR_DELETE);
        assertArrayEquals(new OFPhysicalPort[] { port1, port2 },
                          controller.portsRemoved.toArray());
        assertFalse(controller.portNumToUuid.containsKey(47));
        assertNull(controller.peerOfTunnelPortNum(47));
    }

    @Test
    public void testBringPortDown() {
        assertArrayEquals(new OFPhysicalPort[] { },
                          controller.portsRemoved.toArray());
        assertTrue(controller.portNumToUuid.containsKey(37));
        port1.setConfig(AbstractController.portDownFlag);
        controller.onPortStatus(port1, OFPortReason.OFPPR_MODIFY);
                assertArrayEquals(new OFPhysicalPort[] { port1 },
                          controller.portsRemoved.toArray());
        assertFalse(controller.portNumToUuid.containsKey(37));
        assertTrue(controller.portNumToUuid.containsKey(47));
        assertNotNull(controller.peerOfTunnelPortNum(47));
    }

    @Test
    public void testDeleteDownPort() {
        assertArrayEquals(new OFPhysicalPort[] { },
                          controller.portsRemoved.toArray());
        controller.onPortStatus(port3, OFPortReason.OFPPR_DELETE);
        assertArrayEquals(new OFPhysicalPort[] { },
                          controller.portsRemoved.toArray());
    }

    @Test
    public void testMakeGREPortName() {
        assertEquals("tne1234ff0011aa", 
                     controller.makeGREPortName(new IntIPv4(0xff0011aa)));
    }

    @Test
    public void testPeerIpOfGrePortName() {
        assertEquals(0xff0011aa,
                controller.peerIpOfGrePortName("tne1234ff0011aa").address);
    }

    @Test
    public void testPeerIpToTunnelPortNum() {
        IntIPv4 peerIP = IntIPv4.fromString("192.168.1.53");
        String grePortName = controller.makeGREPortName(peerIP);
        assertEquals(peerIP, controller.peerIpOfGrePortName(grePortName));

        OFPhysicalPort port = new OFPhysicalPort();
        port.setPortNumber((short) 54);
        port.setHardwareAddress(new byte[] { 10, 12, 13, 14, 15, 54 });
        port.setName(grePortName);
        ovsdb.setPortExternalId(dp_id, 54, "midonet", 
                                UUID.randomUUID().toString());
        controller.onPortStatus(port, OFPortReason.OFPPR_ADD);
        log.debug("peerIP: {}", peerIP);
        assertEquals(new Integer(54), 
                     controller.tunnelPortNumOfPeer(peerIP));
    }

    @Test
    public void testPortLocMapListener() throws KeeperException {
        ovsdb.addedGrePorts.clear();
        UUID portUuid = UUID.randomUUID();
        String path1 = "/"+portUuid.toString()+",255.0.17.170,";
        String path2 = "/"+portUuid.toString()+",255.0.17.172,";

        // Port comes up.  Verify tunnel made.
        String fullpath1 = mockDir.add(path1, null,
                                       CreateMode.PERSISTENT_SEQUENTIAL);
        portLocMap.start();
        assertEquals(1, ovsdb.addedGrePorts.size());
        assertTrue((new MockOpenvSwitchDatabaseConnection.GrePort(
                            "43", "tne1234ff0011aa", "255.0.17.170")).equals(
                   ovsdb.addedGrePorts.get(0)));
        assertEquals(0xff0011aa, portLocMap.get(portUuid).address);
        assertEquals(0, ovsdb.deletedPorts.size());

        // Port moves.  Verify old tunnel rm'd, new tunnel made.
        String fullpath2 = mockDir.add(path2, null,
                                       CreateMode.PERSISTENT_SEQUENTIAL);
        mockDir.delete(fullpath1);
        assertEquals(2, ovsdb.addedGrePorts.size());
        assertTrue((new MockOpenvSwitchDatabaseConnection.GrePort(
                            "43", "tne1234ff0011ac", "255.0.17.172")).equals(
                   ovsdb.addedGrePorts.get(1)));
        assertEquals(0xff0011ac, portLocMap.get(portUuid).address);
        assertEquals(1, ovsdb.deletedPorts.size());
        assertEquals("tne1234ff0011aa", ovsdb.deletedPorts.get(0));

        // Port doesn't move.  Verify tunnel not rm'd.
        String path3 = mockDir.add(path2, null,
                                   CreateMode.PERSISTENT_SEQUENTIAL);
        mockDir.delete(fullpath2);
        assertEquals(1, ovsdb.deletedPorts.size());

        // Port goes down.  Verify tunnel rm'd.
        log.info("Deleting path {}", path3);
        mockDir.delete(path3);
        assertEquals(2, ovsdb.deletedPorts.size());
        assertEquals("tne1234ff0011ac", ovsdb.deletedPorts.get(1));
    }

    @Test
    public void testGetGreKey() {
        assertEquals(0xe1234, controller.getGreKey());
    }

    @Test
    public void testLLDPFlowMatch() {
        LLDP packet = new LLDP();
        LLDPTLV chassis = new LLDPTLV();
        chassis.setType((byte)0xca);
        chassis.setLength((short)7);
        chassis.setValue("chassis".getBytes());
        LLDPTLV port = new LLDPTLV();
        port.setType((byte)0);
        port.setLength((short)4);
        port.setValue("port".getBytes());
        LLDPTLV ttl = new LLDPTLV();
        ttl.setType((byte)40);
        ttl.setLength((short)3);
        ttl.setValue("ttl".getBytes());
        packet.setChassisId(chassis);
        packet.setPortId(port);
        packet.setTtl(ttl);

        Ethernet frame = new Ethernet();
        frame.setPayload(packet);
        frame.setEtherType(LLDP.ETHERTYPE);
        MAC dstMac = MAC.fromString("00:11:22:33:44:55");
        MAC srcMac = MAC.fromString("66:55:44:33:22:11");
        frame.setDestinationMACAddress(dstMac);
        frame.setSourceMACAddress(srcMac);
        byte[] pktData = frame.serialize();

        assertEquals(0, controllerStub.addedFlows.size());
        assertEquals(0, controllerStub.sentPackets.size());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        controller.onPacketIn(-1, pktData.length, (short)1, pktData);
        MidoMatch expectMatch = new MidoMatch();
        expectMatch.setDataLayerType(LLDP.ETHERTYPE);
        expectMatch.setDataLayerSource(srcMac);
        expectMatch.setDataLayerDestination(dstMac);
        expectMatch.setInputPort((short)1);
        assertEquals(1, controllerStub.addedFlows.size());
        assertEquals(expectMatch, controllerStub.addedFlows.get(0).match);
        assertEquals(0, controllerStub.sentPackets.size());
        assertEquals(1, controllerStub.droppedPktBufIds.size());
        assertEquals(-1, controllerStub.droppedPktBufIds.get(0).intValue());
    }

    @Test
    public void testIGMPFlowMatch() {
        // Real IGMP packet sniffed off the net.
        String frameHexDump = "01005e0000fb001b21722f2b080046c0002000004000" +
                              "01027233708c205de00000fb9404000016000904e000" +
                              "00fb0000000000000000000000000000";
        MAC srcMac = MAC.fromString("00:1b:21:72:2f:2b");
        MAC dstMac = MAC.fromString("01:00:5e:00:00:fb");
        int srcIP = Net.convertStringAddressToInt("112.140.32.93");
        int dstIP = Net.convertStringAddressToInt("224.0.0.251");
        byte diffServ = (byte)0xC0;

        assertEquals(60*2, frameHexDump.length());
        byte[] pktData = new byte[60];
        for (int i = 0; i < 60; i++) {
            pktData[i] = (byte)(
                (Character.digit(frameHexDump.charAt(2*i), 16) << 4) |
                (Character.digit(frameHexDump.charAt(2*i+1), 16)));
        }

        assertEquals(0, controllerStub.addedFlows.size());
        assertEquals(0, controllerStub.sentPackets.size());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        controller.onPacketIn(76, pktData.length, (short)-1, pktData);
        MidoMatch expectMatch = new MidoMatch();
        expectMatch.setDataLayerType(IPv4.ETHERTYPE);
        expectMatch.setDataLayerSource(srcMac);
        expectMatch.setDataLayerDestination(dstMac);
        // expectMatch.setNetworkTypeOfService(diffServ);
        expectMatch.setNetworkProtocol((byte)2 /* IGMP */);
        expectMatch.setNetworkSource(srcIP);
        expectMatch.setNetworkDestination(dstIP);

        assertEquals(1, controllerStub.addedFlows.size());
        assertEquals(expectMatch, controllerStub.addedFlows.get(0).match);
        assertArrayEquals(new OFAction[]{}, 
                          controllerStub.addedFlows.get(0).actions.toArray());
        assertEquals(0, controllerStub.sentPackets.size());
        assertEquals(0, controllerStub.droppedPktBufIds.size());

        OFAction[] flowActions = { new OFActionOutput((short)1, (short)2) };
        controller.flowActions = flowActions;
        controller.onPacketIn(-1, pktData.length, (short)-1, pktData);
        assertEquals(2, controllerStub.addedFlows.size());
        assertEquals(expectMatch, controllerStub.addedFlows.get(1).match);
        assertArrayEquals(flowActions,
                          controllerStub.addedFlows.get(1).actions.toArray());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        assertEquals(1, controllerStub.sentPackets.size());
        assertArrayEquals(pktData, controllerStub.sentPackets.get(0).data);
    }

    @Test 
    public void testTCPDropAndNotDrop() {
        //TCP xport = new TCP();
        // Can't construct a TCP packet with the packets package because
        // TCP.serialize() is unimplemented.
        MAC srcMac = MAC.fromString("00:18:e7:dd:1c:b4");
        MAC dstMac = MAC.fromString("10:9a:dd:4c:6f:49");
        int srcIP = Net.convertStringAddressToInt("204.152.18.196");
        int dstIP = Net.convertStringAddressToInt("192.168.1.143");
        short srcPort = 443;
        short dstPort = (short)36911;

        /* Real Ethernet [IP/TCP] frame sniffed off the net. */
        String frameHexDump = 
                "109add4c6f490018e7dd1cb408004500015f2f8d4000f106b777cc9812" +
                "c4c0a8018f01bb902f5385670e2c5417cb50189ffeb183000017030101" +
                "320c06e1c9e3d422b90c91caf2a4f773c1a8996b1f435586f21c8b03f3" +
                "24ba5c94335d89849c9552180f94826c82860cbcd7cc42990e5c9442b7" +
                "f815f46d17512e47125d295a7cc62106e058a41d944f5f43a10ef37f02" +
                "9ba63fdeffe60b63c02ec129509d37852a6f378fb9ec3d64cd186c458b" +
                "d5e9a32778c879bc1595c604b252b00f02f8baf82c17664e91ee65e093" +
                "04c5f603ac41953234e71a304790f597261f3bd593a9b1bddf085b642d" +
                "f7ffe43a554fcd09f1deff0f7ffe519a1959e695909d4db805a0b1aed5" +
                "1a06bef1b6f98106985e227f2a9ca3469553aa99a53e46a64517eb219f" +
                "c68454ae123e2868a19e209d429e60cc72e5815df1526c3e0de0e036ce" +
                "d7996e5a3d7137618ef311fcb0f8d73f6437695fee3b7f720a3db4e31b" +
                "927d5a0ff58ad57a319d2e6fae4545d3a9";
        assertEquals(365*2, frameHexDump.length());
        byte[] pktData = new byte[365];
        for (int i = 0; i < 365; i++) {
            pktData[i] = (byte)(
                (Character.digit(frameHexDump.charAt(2*i), 16) << 4) |
                (Character.digit(frameHexDump.charAt(2*i+1), 16)));
        }

        assertEquals(0, controllerStub.addedFlows.size());
        assertEquals(0, controllerStub.sentPackets.size());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        controller.onPacketIn(76, pktData.length, (short)-1, pktData);
        MidoMatch expectMatch = new MidoMatch();
        expectMatch.setDataLayerType(IPv4.ETHERTYPE);
        expectMatch.setDataLayerSource(srcMac);
        expectMatch.setDataLayerDestination(dstMac);
        // expectMatch.setNetworkTypeOfService((byte)0);
        expectMatch.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        expectMatch.setNetworkSource(srcIP);
        expectMatch.setNetworkDestination(dstIP);
        expectMatch.setTransportSource(srcPort);
        expectMatch.setTransportDestination(dstPort);

        assertEquals(1, controllerStub.addedFlows.size());
        assertEquals(expectMatch, controllerStub.addedFlows.get(0).match);
        assertArrayEquals(new OFAction[]{}, 
                          controllerStub.addedFlows.get(0).actions.toArray());
        assertEquals(0, controllerStub.sentPackets.size());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        // TODO: Is it possible to "drop" a packet with a bufferID != -1 ?
        //assertEquals(76, controllerStub.droppedPktBufIds.get(0).intValue());

        OFAction[] flowActions = { new OFActionOutput((short)1, (short)2) };
        controller.flowActions = flowActions;
        controller.onPacketIn(-1, pktData.length, (short)-1, pktData);
        assertEquals(2, controllerStub.addedFlows.size());
        assertEquals(expectMatch, controllerStub.addedFlows.get(1).match);
        assertArrayEquals(flowActions,
                          controllerStub.addedFlows.get(1).actions.toArray());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        assertEquals(1, controllerStub.sentPackets.size());
        assertArrayEquals(pktData, controllerStub.sentPackets.get(0).data);
    }

    @Test
    public void testUDPNoDrop() {
        UDP xport = new UDP();
        xport.setSourcePort((short)17234);
        xport.setDestinationPort((short)52956);
        IPv4 packet = new IPv4();
        packet.setPayload(xport);
        packet.setProtocol(UDP.PROTOCOL_NUMBER);
        packet.setDiffServ((byte)0x14);
        Ethernet frame = new Ethernet();
        frame.setPayload(packet);
        frame.setEtherType(IPv4.ETHERTYPE);
        MAC dstMac = MAC.fromString("00:11:22:33:44:55");
        MAC srcMac = MAC.fromString("66:55:44:33:22:11");
        frame.setDestinationMACAddress(dstMac);
        frame.setSourceMACAddress(srcMac);
        byte[] pktData = frame.serialize();

        OFAction[] flowActions = { new OFActionOutput((short)1, (short)2) };
        controller.flowActions = flowActions;
        assertEquals(0, controllerStub.addedFlows.size());
        assertEquals(0, controllerStub.sentPackets.size());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        controller.onPacketIn(-1, pktData.length, (short)-1, pktData);
        MidoMatch expectMatch = new MidoMatch();
        expectMatch.setDataLayerType(IPv4.ETHERTYPE);
        expectMatch.setDataLayerSource(srcMac);
        expectMatch.setDataLayerDestination(dstMac);
        // expectMatch.setNetworkTypeOfService((byte)0x14);
        expectMatch.setNetworkProtocol(UDP.PROTOCOL_NUMBER);
        expectMatch.setNetworkSource(0);
        expectMatch.setNetworkDestination(0);
        expectMatch.setTransportSource((short)17234);
        expectMatch.setTransportDestination((short)52956);
        assertEquals(1, controllerStub.addedFlows.size());
        assertEquals(expectMatch, controllerStub.addedFlows.get(0).match);
        assertArrayEquals(flowActions, 
                          controllerStub.addedFlows.get(0).actions.toArray());
        assertEquals(0, controllerStub.droppedPktBufIds.size());
        assertEquals(1, controllerStub.sentPackets.size());
        assertArrayEquals(pktData, controllerStub.sentPackets.get(0).data);
    }
}
