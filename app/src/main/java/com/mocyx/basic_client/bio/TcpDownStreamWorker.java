package com.mocyx.basic_client.bio;

import android.util.Log;

import com.mocyx.basic_client.protocol.tcpip.Packet;
import com.mocyx.basic_client.protocol.tcpip.TCBStatus;
import com.mocyx.basic_client.util.ProxyException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

class TcpDownStreamWorker implements Runnable {
    TcpTunnel tunnel;

    public TcpDownStreamWorker(TcpTunnel tunnel) {
        this.tunnel = tunnel;
    }

    @Override
    public void run() {
        ByteBuffer buffer = ByteBuffer.allocate(4 * 1024);

        String quitType = "rst";

        try {
            while (true) {
                buffer.clear();
                if (tunnel.destSocket == null) {
                    throw new ProxyException("tunnel maybe closed");
                }
                int n = BioUtil.read(tunnel.destSocket, buffer);

                synchronized (tunnel) {
                    if (n == -1) {
                        quitType = "fin";
                        break;
                    } else {
                        if (tunnel.tcbStatus != TCBStatus.CLOSE_WAIT) {
                            buffer.flip();
                            byte[] data = new byte[buffer.remaining()];
                            buffer.get(data);
                            BioTcpHandler.sendTcpPack(tunnel, (byte) (Packet.TCPHeader.ACK), data);
                        }
                    }
                }
            }
        } catch (ClosedChannelException e) {
            Log.w(BioTcpHandler.TAG, String.format("channel closed %s", e.getMessage()));
            quitType = "rst";
        } catch (IOException e) {
            Log.e(BioTcpHandler.TAG, e.getMessage(), e);
            quitType = "rst";
        } catch (Exception e) {
            quitType = "rst";
            Log.e(BioTcpHandler.TAG, "DownStreamWorker fail", e);
        }
        //Log.i(TAG, String.format("DownStreamWorker quit %d", tunnel.tunnelId));
        synchronized (tunnel) {
            if (quitType.equals("fin")) {
                BioTcpHandler.closeDownStream(tunnel);
                //closeUpStream(tunnel);
                //closeRst(tunnel);
            } else if (quitType.equals("rst")) {
                BioTcpHandler.closeRst(tunnel);
            }

        }

    }
}
