package com.mocyx.basic_client.protocol.tcpip;

class BitUtils {
    public static short getUnsignedByte(byte value) {
        return (short) (value & 0xFF);
    }

    public static int getUnsignedShort(short value) {
        return value & 0xFFFF;
    }

    public static long getUnsignedInt(int value) {
        return value & 0xFFFFFFFFL;
    }
}
