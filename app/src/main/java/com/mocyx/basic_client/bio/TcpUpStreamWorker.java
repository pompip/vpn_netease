package com.mocyx.basic_client.bio;

import android.util.Log;

import com.mocyx.basic_client.config.Config;
import com.mocyx.basic_client.protocol.tcpip.Packet;
import com.mocyx.basic_client.protocol.tcpip.TCBStatus;
import com.mocyx.basic_client.protocol.tcpip.TCPHeader;
import com.mocyx.basic_client.util.ProxyException;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

class TcpUpStreamWorker implements Runnable {

    TcpTunnel tunnel;

    public TcpUpStreamWorker(TcpTunnel tunnel) {
        this.tunnel = tunnel;
    }


    private void startDownStream() {
        Thread t = new Thread(new TcpDownStreamWorker(tunnel));
        t.start();
    }

    private void connectRemote() {
        try {
            //connect
            SocketChannel remote = SocketChannel.open();
            tunnel.vpnService.protect(remote.socket());
//            InetSocketAddress address = tunnel.destinationAddress;
            InetSocketAddress address = new InetSocketAddress(Inet4Address.getByName("me.pompip.cn"), 10086);
            Long ts = System.currentTimeMillis();
            remote.socket().connect(address, 5000);
            Long te = System.currentTimeMillis();

            Log.i(BioTcpHandler.TAG, String.format("connectRemote %d cost %d  remote %s", tunnel.tunnelId, te - ts, tunnel.destinationAddress.toString()));
            tunnel.destSocket = remote;

            startDownStream();
        } catch (Exception e) {
            Log.e(BioTcpHandler.TAG, e.getMessage(), e);
            throw new ProxyException("connectRemote fail" + tunnel.destinationAddress.toString());
        }
    }

    int synCount = 0;

    private void handleSyn(Packet packet) {

        if (tunnel.tcbStatus == TCBStatus.SYN_SENT) {
            tunnel.tcbStatus = TCBStatus.SYN_RECEIVED;
        }
        Log.i(BioTcpHandler.TAG, String.format("handleSyn  %d %d", tunnel.tunnelId, packet.packId));
        TCPHeader tcpHeader = packet.tcpHeader;
        if (synCount == 0) {
            tunnel.mySequenceNum = 1;
            tunnel.theirSequenceNum = tcpHeader.sequenceNumber;
            tunnel.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
            tunnel.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;
            BioTcpHandler.sendTcpPack(tunnel, (byte) (TCPHeader.SYN | TCPHeader.ACK), null);
        } else {
            tunnel.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
        }
        synCount += 1;
    }


    private void writeToRemote(ByteBuffer buffer) throws IOException {
        if (tunnel.upActive) {
            int payloadSize = buffer.remaining();
            while (buffer.hasRemaining()){
                int write = tunnel.destSocket.write(buffer);
            }

        }
    }

    private void handleAck(Packet packet) throws IOException {

        if (tunnel.tcbStatus == TCBStatus.SYN_RECEIVED) {
            tunnel.tcbStatus = TCBStatus.ESTABLISHED;

        }

        if (Config.logAck) {
            Log.d(BioTcpHandler.TAG, String.format("handleAck %d ", packet.packId));
        }

        TCPHeader tcpHeader = packet.tcpHeader;
        int payloadSize = packet.backingBuffer.remaining();

        if (payloadSize == 0) {
            return;
        }

        long newAck = tcpHeader.sequenceNumber + payloadSize;
        if (newAck <= tunnel.myAcknowledgementNum) {
            if (Config.logAck) {
                Log.d(BioTcpHandler.TAG, String.format("handleAck duplicate ack", tunnel.myAcknowledgementNum, newAck));
            }
            return;
        }

        tunnel.myAcknowledgementNum = tcpHeader.sequenceNumber;
        tunnel.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;

        tunnel.myAcknowledgementNum += payloadSize;
        writeToRemote(packet.backingBuffer);

        BioTcpHandler.sendTcpPack(tunnel, (byte) TCPHeader.ACK, null);

        System.currentTimeMillis();
    }

    private void handleFin(Packet packet) {
        Log.i(BioTcpHandler.TAG, String.format("handleFin %d", tunnel.tunnelId));
        tunnel.myAcknowledgementNum = packet.tcpHeader.sequenceNumber + 1;
        tunnel.theirAcknowledgementNum = packet.tcpHeader.acknowledgementNumber;
        BioTcpHandler.sendTcpPack(tunnel, (byte) (TCPHeader.ACK), null);
        //closeTunnel(tunnel);
        //closeDownStream();
        BioTcpHandler.closeUpStream(tunnel);
        tunnel.tcbStatus = TCBStatus.CLOSE_WAIT;
    }

    private void handleRst(Packet packet) {
        Log.i(BioTcpHandler.TAG, String.format("handleRst %d", tunnel.tunnelId));
        try {
            synchronized (tunnel) {
                if (tunnel.destSocket != null) {
                    tunnel.destSocket.close();
                }

            }
        } catch (IOException e) {
            Log.e(BioTcpHandler.TAG, "close error", e);
        }

        synchronized (tunnel) {
            tunnel.upActive = false;
            tunnel.downActive = false;
            tunnel.tcbStatus = TCBStatus.CLOSE_WAIT;
        }
    }


    private void loop() {
        while (true) {
            Packet packet = null;
            try {
                packet = tunnel.tunnelInputQueue.take();

                //Log.i(TAG, "lastIdentification " + tunnel.lastIdentification);
                synchronized (tunnel) {
                    boolean end = false;
                    TCPHeader tcpHeader = packet.tcpHeader;

                    if (tcpHeader.isSYN()) {
                        handleSyn(packet);
                        end = true;
                    }
                    if (!end && tcpHeader.isRST()) {
                        //
                        //Log.i(TAG, String.format("handleRst %d", tunnel.tunnelId));
                        //tunnel.destSocket.close();
                        handleRst(packet);
                        end = true;
                        break;
                    }
                    if (!end && tcpHeader.isFIN()) {
                        handleFin(packet);
                        end = true;
                    }
                    if (!end && tcpHeader.isACK()) {
                        handleAck(packet);
                    }
//                        if (!tunnel.downActive && !tunnel.upActive) {
//                            closeTotalTunnel();
//                            break;
//                        }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        }
        Log.i(BioTcpHandler.TAG, String.format("UpStreamWorker quit"));
    }

    @Override
    public void run() {
        try {
            connectRemote();
            loop();
        } catch (ProxyException e) {
            //closeTotalTunnel();
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
