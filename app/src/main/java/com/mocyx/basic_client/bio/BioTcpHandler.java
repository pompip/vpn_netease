package com.mocyx.basic_client.bio;

import android.net.VpnService;
import android.os.Build;
import android.util.Log;

import com.mocyx.basic_client.protocol.tcpip.IpUtil;
import com.mocyx.basic_client.util.ByteBufferPool;
import com.mocyx.basic_client.protocol.tcpip.Packet;
import com.mocyx.basic_client.protocol.tcpip.Packet.TCPHeader;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

public class BioTcpHandler implements Runnable {

    BlockingQueue<Packet> queue;

    ConcurrentHashMap<String, TcpTunnel> tunnels = new ConcurrentHashMap();


    private static int HEADER_SIZE = Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE;

    public static final String TAG = BioTcpHandler.class.getSimpleName();

    private VpnService vpnService;
    BlockingQueue<ByteBuffer> networkToDeviceQueue;

    public BioTcpHandler(BlockingQueue<Packet> queue, BlockingQueue<ByteBuffer> networkToDeviceQueue, VpnService vpnService) {
        this.queue = queue;
        this.vpnService = vpnService;
        this.networkToDeviceQueue = networkToDeviceQueue;
    }

    protected static void sendTcpPack(TcpTunnel tunnel, byte flag, byte[] data) {

        int dataLen = 0;
        if (data != null) {
            dataLen = data.length;
        }
        Packet packet = IpUtil.buildTcpPacket(tunnel.destinationAddress, tunnel.sourceAddress, flag,
                tunnel.myAcknowledgementNum, tunnel.mySequenceNum, tunnel.packId);
        tunnel.packId += 1;
        ByteBuffer byteBuffer = ByteBufferPool.acquire();
        //
        byteBuffer.position(HEADER_SIZE);
        if (data != null) {
            if (byteBuffer.remaining() < data.length) {
                System.currentTimeMillis();
            }
            byteBuffer.put(data);
        }

        packet.updateTCPBuffer(byteBuffer, flag, tunnel.mySequenceNum, tunnel.myAcknowledgementNum, dataLen);
        byteBuffer.position(HEADER_SIZE + dataLen);

        tunnel.networkToDeviceQueue.offer(byteBuffer);

        if ((flag & (byte) TCPHeader.SYN) != 0) {
            tunnel.mySequenceNum += 1;
        }
        if ((flag & (byte) TCPHeader.FIN) != 0) {
            tunnel.mySequenceNum += 1;
        }
        if ((flag & (byte) TCPHeader.ACK) != 0) {
            tunnel.mySequenceNum += dataLen;
        }
    }

    public static boolean isClosedTunnel(TcpTunnel tunnel) {
        return !tunnel.upActive && !tunnel.downActive;
    }

    public static void closeDownStream(TcpTunnel tunnel) {
        synchronized (tunnel) {
            Log.i(TAG, String.format("closeDownStream %d", tunnel.tunnelId));
            try {
                if (tunnel.destSocket != null && tunnel.destSocket.isOpen()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        tunnel.destSocket.shutdownInput();
                    } else {
                        tunnel.destSocket.close();
                        tunnel.destSocket = null;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            sendTcpPack(tunnel, (byte) (TCPHeader.FIN | Packet.TCPHeader.ACK), null);
            tunnel.downActive = false;
            if (isClosedTunnel(tunnel)) {
                tunnel.tunnelCloseMsgQueue.add(tunnel.tunnelKey);
            }
        }
    }

    public static void closeUpStream(TcpTunnel tunnel) {
        synchronized (tunnel) {
            Log.i(TAG, String.format("closeUpStream %d", tunnel.tunnelId));
            try {
                if (tunnel.destSocket != null && tunnel.destSocket.isOpen()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        tunnel.destSocket.shutdownOutput();
                    } else {
                        tunnel.destSocket.close();
                        tunnel.destSocket = null;
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
            Log.i(TAG, String.format("closeUpStream %d", tunnel.tunnelId));
            tunnel.upActive = false;
            if (isClosedTunnel(tunnel)) {
                tunnel.tunnelCloseMsgQueue.add(tunnel.tunnelKey);
            }
        }
    }

    protected static void closeRst(TcpTunnel tunnel) {
        synchronized (tunnel) {
            Log.i(TAG, String.format("closeRst %d", tunnel.tunnelId));
            try {
                if (tunnel.destSocket != null && tunnel.destSocket.isOpen()) {
                    tunnel.destSocket.close();
                    tunnel.destSocket = null;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            sendTcpPack(tunnel, (byte) TCPHeader.RST, null);
            tunnel.upActive = false;
            tunnel.downActive = false;
        }
    }

    protected TcpTunnel initTunnel(Packet packet) throws UnknownHostException {
        TcpTunnel tunnel = new TcpTunnel();
        tunnel.sourceAddress = new InetSocketAddress(packet.ip4Header.sourceAddress, packet.tcpHeader.sourcePort);
        tunnel.destinationAddress = new InetSocketAddress(packet.ip4Header.destinationAddress, packet.tcpHeader.destinationPort);
//        tunnel.destinationAddress = new InetSocketAddress(Inet4Address.getByName("192.168.2.100"), 10086);
        tunnel.vpnService = vpnService;
        tunnel.networkToDeviceQueue = networkToDeviceQueue;
        tunnel.tunnelCloseMsgQueue = tunnelCloseMsgQueue;
        Thread t = new Thread(new TcpUpStreamWorker(tunnel));
        t.start();

        return tunnel;
    }

    public BlockingQueue<String> tunnelCloseMsgQueue = new ArrayBlockingQueue<>(1024);

    @Override
    public void run() {

        while (true) {
            try {
                Packet currentPacket = queue.take();

                InetAddress destinationAddress = currentPacket.ip4Header.destinationAddress;

                TCPHeader tcpHeader = currentPacket.tcpHeader;
                //Log.d(TAG, String.format("get pack %d tcp " + tcpHeader.printSimple() + " ", currentPacket.packId));

                int destinationPort = tcpHeader.destinationPort;
                int sourcePort = tcpHeader.sourcePort;
                String ipAndPort = destinationAddress.getHostAddress() + ":" +
                        destinationPort + ":" + sourcePort;

                while (true) {
                    String s = this.tunnelCloseMsgQueue.poll();
                    if (s == null) {
                        break;
                    } else {
                        tunnels.remove(ipAndPort);
                        Log.i(TAG, String.format("remove tunnel %s", ipAndPort));
                    }
                }


                if (!tunnels.containsKey(ipAndPort)) {
                    TcpTunnel tcpTunnel = initTunnel(currentPacket);
                    tcpTunnel.tunnelKey = ipAndPort;
                    tunnels.put(ipAndPort, tcpTunnel);
                }
                TcpTunnel tcpTunnel = tunnels.get(ipAndPort);
                //
                tcpTunnel.tunnelInputQueue.offer(currentPacket);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
