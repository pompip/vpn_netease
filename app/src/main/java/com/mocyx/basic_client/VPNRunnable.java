package com.mocyx.basic_client;

import android.util.Log;

import com.mocyx.basic_client.config.Config;
import com.mocyx.basic_client.protocol.tcpip.Packet;
import com.mocyx.basic_client.util.ByteBufferPool;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.BlockingQueue;

class VPNRunnable implements Runnable {
    private static final String TAG = "VPNRunnable";

    private FileDescriptor vpnFileDescriptor;

    private BlockingQueue<Packet> deviceToNetworkUDPQueue;
    private BlockingQueue<Packet> deviceToNetworkTCPQueue;
    private BlockingQueue<ByteBuffer> networkToDeviceQueue;

    public VPNRunnable(FileDescriptor vpnFileDescriptor,
                       BlockingQueue<Packet> deviceToNetworkUDPQueue,
                       BlockingQueue<Packet> deviceToNetworkTCPQueue,
                       BlockingQueue<ByteBuffer> networkToDeviceQueue) {
        this.vpnFileDescriptor = vpnFileDescriptor;
        this.deviceToNetworkUDPQueue = deviceToNetworkUDPQueue;
        this.deviceToNetworkTCPQueue = deviceToNetworkTCPQueue;
        this.networkToDeviceQueue = networkToDeviceQueue;
    }


    @Override
    public void run() {
        Log.i(TAG, "Started");

        FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();
        FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();
        Thread t = new Thread(new WriteVpnThread(vpnOutput, networkToDeviceQueue));
        t.start();

        try {
            ByteBuffer bufferToNetwork = null;

            while (!Thread.interrupted()) {
                bufferToNetwork = ByteBufferPool.acquire();
                int readBytes = vpnInput.read(bufferToNetwork);

                MainActivity.upByte.addAndGet(readBytes);

                if (readBytes > 0) {
                    bufferToNetwork.flip();

                    Packet packet = new Packet(bufferToNetwork);
                    if (packet.isUDP()) {
                        if (Config.logRW) {
                            Log.i(TAG, "read udp" + readBytes);
                        }
                        deviceToNetworkUDPQueue.offer(packet);
                    } else if (packet.isTCP()) {
                        if (Config.logRW) {
                            Log.i(TAG, "read tcp " + readBytes);
                        }
                        deviceToNetworkTCPQueue.offer(packet);
                    } else {
                        Log.w(TAG, String.format("Unknown packet protocol type %d", packet.ip4Header.protocolNum));
                    }
                } else {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, e.toString(), e);
        } finally {
            LocalVPNService.closeResources(vpnInput, vpnOutput);
        }
    }
}
