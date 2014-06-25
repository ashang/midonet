/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.odp.OpenVSwitch;

public class FlowKeyInPort implements CachedFlowKey {

    /*__u32*/ private int portNo;

    // This is used for deserialization purposes only.
    FlowKeyInPort() { }

    FlowKeyInPort(int portNo) {
        this.portNo = portNo;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putInt(portNo);
        return 4;
    }

    public void deserializeFrom(ByteBuffer buf) {
        portNo = buf.getInt();
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.InPort;
    }

    public int getInPort() {
        return this.portNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyInPort that = (FlowKeyInPort) o;

        return this.portNo == that.portNo;
    }

    @Override
    public int hashCode() {
        return portNo;
    }

    @Override
    public String toString() {
        return "FlowKeyInPort{portNo=" + portNo + '}';
    }
}
