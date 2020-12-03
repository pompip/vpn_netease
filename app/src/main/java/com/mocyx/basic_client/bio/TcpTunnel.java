package com.mocyx.basic_client.bio;

import android.net.VpnService;

import com.mocyx.basic_client.protocol.tcpip.Packet;
import com.mocyx.basic_client.protocol.tcpip.TCBStatus;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

class TcpTunnel {

    static AtomicInteger tunnelIds = new AtomicInteger(0);
    public final int tunnelId = tunnelIds.addAndGet(1);

    public long mySequenceNum = 0;
    public long theirSequenceNum = 0;
    public long myAcknowledgementNum = 0;
    public long theirAcknowledgementNum = 0;

    public TCBStatus tcbStatus = TCBStatus.SYN_SENT;
    public BlockingQueue<Packet> tunnelInputQueue = new ArrayBlockingQueue<Packet>(1024);
    public InetSocketAddress sourceAddress;
    public InetSocketAddress destinationAddress;
    public SocketChannel destSocket;
    public VpnService vpnService;
    BlockingQueue<ByteBuffer> networkToDeviceQueue;

    public int packId = 1;

    public boolean upActive = true;
    public boolean downActive = true;
    public String tunnelKey;
    public BlockingQueue<String> tunnelCloseMsgQueue;

}
