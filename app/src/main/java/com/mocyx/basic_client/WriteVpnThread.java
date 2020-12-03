package com.mocyx.basic_client;

import android.util.Log;

import com.mocyx.basic_client.config.Config;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.BlockingQueue;

class WriteVpnThread implements Runnable {
    private static final String TAG = "WriteVpnThread";
    FileChannel vpnOutput;
    private BlockingQueue<ByteBuffer> networkToDeviceQueue;

    WriteVpnThread(FileChannel vpnOutput, BlockingQueue<ByteBuffer> networkToDeviceQueue) {
        this.vpnOutput = vpnOutput;
        this.networkToDeviceQueue = networkToDeviceQueue;
    }

    @Override
    public void run() {
        while (true) {
            try {
                ByteBuffer bufferFromNetwork = networkToDeviceQueue.take();
                bufferFromNetwork.flip();

                while (bufferFromNetwork.hasRemaining()) {
                    int w = vpnOutput.write(bufferFromNetwork);
                    if (w > 0) {
                        MainActivity.downByte.addAndGet(w);
                    }

                    if (Config.logRW) {
                        Log.d(TAG, "vpn write " + w);
                    }
                }
            } catch (Exception e) {
                Log.i(TAG, "WriteVpnThread fail", e);
            }

        }

    }
}
