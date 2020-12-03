package com.mocyx.basic_client.bio;

import android.util.Log;

import com.mocyx.basic_client.protocol.tcpip.TCBStatus;
import com.mocyx.basic_client.protocol.tcpip.TCPHeader;
import com.mocyx.basic_client.util.ProxyException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

class TcpDownStreamWorker implements Runnable {
    private static final String TAG = "TcpDownStreamWorker";
    final TcpTunnel tunnel;

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

                int n = tunnel.destSocket.read( buffer);

                synchronized (tunnel) {
                    if (n == -1) {
                        quitType = "fin";
                        break;
                    } else {
                        if (tunnel.tcbStatus != TCBStatus.CLOSE_WAIT) {
                            buffer.flip();
                            byte[] data = new byte[buffer.remaining()];
                            buffer.get(data);
                            BioTcpHandler.sendTcpPack(tunnel, (byte) (TCPHeader.ACK), data);
                        }
                    }
                }
            }
        } catch (ClosedChannelException e) {
            Log.e(TAG, "channel closed ",e);
            quitType = "rst";
        } catch (IOException e) {
            Log.e(TAG, e.getMessage(), e);
            quitType = "rst";
        } catch (Exception e) {
            quitType = "rst";
            Log.e(TAG, "DownStreamWorker fail", e);
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
