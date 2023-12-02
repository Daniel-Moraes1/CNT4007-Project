package src;

import java.util.BitSet;

public class Util {

    static public int fourBytesToInt(byte[] bytes) {
        int sum = 0;
        for (byte b: bytes) {

            sum = (sum << 8) + (b&0xff);
        }
        return sum;
    }

    static public byte[] intToFourBytes(int num) {
        return new byte[]{
                (byte) (num >> 24),
                (byte) (num >> 16),
                (byte) (num >> 8),
                (byte) num
        };
    }
}
